package com.krystal.vectorsidecarservice.application.data;

import com.krystal.vectorsidecarservice.application.port.in.UpdateVectorDataUseCase;
import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorDataRelationalPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorOutboxEventPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorPayloadFieldPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorSourceVersionPort;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorColumnLifecycle;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorPayloadFieldMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UpdateVectorDataService implements UpdateVectorDataUseCase {

    private static final String DEFAULT_TENANT = "DEFAULT";
    private static final String DEFAULT_SCHEMA = "PUBLIC";
    private static final String ROW_VERSION_COLUMN = "ROW_VERSION";
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final VectorMetadataPort vectorMetadataPort;
    private final VectorCollectionPort vectorCollectionPort;
    private final VectorPayloadFieldPort vectorPayloadFieldPort;
    private final RelationalSchemaPort relationalSchemaPort;
    private final VectorDataRelationalPort vectorDataRelationalPort;
    private final VectorOutboxEventPort vectorOutboxEventPort;
    private final VectorSourceVersionPort vectorSourceVersionPort;
    private final IdGeneratorPort idGenerator;
    private final VectorValueEncoder vectorValueEncoder;
    private final VectorPointIdNormalizer pointIdNormalizer;

    @Override
    @Transactional
    public UpdateVectorDataResult update(UpdateVectorDataCommand command) {
        if (command == null) {
            throw new BizException("request must not be null");
        }
        String tenantId = normalizeWithDefault(command.tenantId(), DEFAULT_TENANT);
        String schemaName = normalizeIdentifier(command.schemaName(), "schemaName", DEFAULT_SCHEMA);
        String tableName = normalizeIdentifier(command.tableName(), "tableName", null);
        String vectorColumnName = optionalIdentifier(command.vectorColumn(), "vectorColumn");
        if (command.pk() == null) {
            throw new BizException("pk must not be null");
        }
        boolean vectorProvided = vectorProvided(command.vector());
        boolean payloadProvided = command.payload() != null && !command.payload().isEmpty();
        if (!vectorProvided && !payloadProvided) {
            throw new BizException("update must provide vector or payload");
        }

        VectorColumnMeta column = resolveColumn(tenantId, schemaName, tableName, vectorColumnName);
        requireColumnWritableForUpdate(column, vectorProvided);

        byte[] vectorBytes = vectorProvided
                ? vectorValueEncoder.encode(command.vector(), column.dimension(), column.vectorEncoding())
                : null;

        PayloadValues payloadValues = payloadValues(column, command.payload());
        validateNoColumnCollision(column, payloadValues.scalarValues().keySet());
        String rowVersionColumn = relationalSchemaPort.columnExists(column.schemaName(), column.tableName(), ROW_VERSION_COLUMN)
                ? ROW_VERSION_COLUMN
                : null;
        if (!rowExists(column, command.pk(), rowVersionColumn)) {
            throw new BizException("row not found for update: " + pointIdNormalizer.relationalPkText(command.pk()));
        }
        long sourceVersion = nextSourceVersion(column, command.pk());

        int updatedRows = vectorDataRelationalPort.update(
                new VectorDataRelationalPort.UpdateRowCommand(
                        column.schemaName(),
                        column.tableName(),
                        column.pkColumn(),
                        command.pk(),
                        vectorProvided ? column.vectorColumn() : null,
                        vectorBytes,
                        rowVersionColumn,
                        rowVersionColumn == null ? null : sourceVersion,
                        payloadValues.scalarValues()
                )
        );
        if (updatedRows != 1) {
            throw new BizException("row not found for update: " + pointIdNormalizer.relationalPkText(command.pk()));
        }

        boolean vectorSyncRequired = vectorProvided || !payloadValues.qdrantPayload().isEmpty();
        if (!vectorSyncRequired) {
            return relationalOnlyResult(column, sourceVersion);
        }

        VectorCollectionMeta collection = readyCollection(column.columnId());
        VectorPointIdNormalizer.NormalizedPointId pointId = pointIdNormalizer.normalize(command.pk(), collection.qdrantIdType());
        String writeTargetName = writeTargetName(collection);
        VectorOutboxEventMeta outboxEvent = createOutboxEvent(column, command.pk(), pointId.text(), sourceVersion);
        VectorOutboxEventPort.SaveResult outboxSaveResult = vectorOutboxEventPort.enqueueOrMergeActive(outboxEvent);
        VectorOutboxEventMeta persistedOutboxEvent = outboxSaveResult.event();
        if (!outboxSaveResult.created() && "DEAD".equals(persistedOutboxEvent.eventStatus())) {
            throw new BizException("existing qdrant sync event is DEAD; manual retry required: "
                    + persistedOutboxEvent.eventId());
        }

        return new UpdateVectorDataResult(
                column.tenantId(),
                column.schemaName(),
                column.tableName(),
                column.vectorColumn(),
                column.columnId(),
                collection.collectionId(),
                collection.collectionName(),
                writeTargetName,
                pointId.text(),
                persistedOutboxEvent.eventId(),
                persistedOutboxEvent.sourceVersion(),
                true,
                true,
                "DONE".equals(persistedOutboxEvent.eventStatus()) ? "OUTBOX_DONE" : "PENDING_OUTBOX",
                outboxSaveResult.created()
                        ? "qdrant vector upsert event enqueued from update: " + persistedOutboxEvent.eventId()
                        : "qdrant vector upsert event merged from update: " + persistedOutboxEvent.eventId()
        );
    }

    private VectorColumnMeta resolveColumn(String tenantId, String schemaName, String tableName, String vectorColumnName) {
        if (vectorColumnName != null) {
            return vectorMetadataPort.findByIdentity(tenantId, schemaName, tableName, vectorColumnName)
                    .orElseThrow(() -> new BizException("vector column metadata not found: "
                            + tenantId + "." + schemaName + "." + tableName + "." + vectorColumnName));
        }
        List<VectorColumnMeta> columns = vectorMetadataPort.findByTableIdentity(tenantId, schemaName, tableName);
        if (columns.isEmpty()) {
            throw new BizException("vector table metadata not found: " + tenantId + "." + schemaName + "." + tableName);
        }
        if (columns.size() > 1) {
            throw new BizException("vectorColumn is required when table has multiple vector columns: "
                    + tenantId + "." + schemaName + "." + tableName);
        }
        return columns.get(0);
    }

    private void requireColumnWritableForUpdate(VectorColumnMeta column, boolean vectorProvided) {
        if (vectorProvided && !VectorColumnLifecycle.ACTIVE.status().equals(column.status())) {
            throw new BizException("vector column is not ACTIVE: " + column.columnId());
        }
        if (!vectorProvided && VectorColumnLifecycle.DISABLED.status().equals(column.status())) {
            throw new BizException("vector column is DISABLED: " + column.columnId());
        }
        if (!relationalSchemaPort.tableExists(column.schemaName(), column.tableName())) {
            throw new BizException("table does not exist: " + column.schemaName() + "." + column.tableName());
        }
    }

    private boolean vectorProvided(List<Double> vector) {
        return vector != null && !vector.isEmpty();
    }

    private UpdateVectorDataResult relationalOnlyResult(VectorColumnMeta column, long sourceVersion) {
        return new UpdateVectorDataResult(
                column.tenantId(),
                column.schemaName(),
                column.tableName(),
                column.vectorColumn(),
                column.columnId(),
                null,
                null,
                null,
                null,
                null,
                sourceVersion,
                true,
                false,
                "SKIPPED_RELATIONAL_ONLY",
                "updated columns do not require qdrant sync"
        );
    }

    private boolean rowExists(VectorColumnMeta column, Object pk, String rowVersionColumn) {
        return vectorDataRelationalPort.findByPk(
                        new VectorDataRelationalPort.FindRowCommand(
                                column.schemaName(),
                                column.tableName(),
                                column.pkColumn(),
                                pk,
                                column.vectorColumn(),
                                rowVersionColumn,
                                List.of()
                        )
                )
                .isPresent();
    }

    private long nextSourceVersion(VectorColumnMeta column, Object pk) {
        String sourcePk = pointIdNormalizer.relationalPkText(pk);
        return vectorSourceVersionPort.nextVersion(
                new VectorSourceVersionPort.NextVersionCommand(
                        column.tenantId(),
                        column.columnId(),
                        sourcePk,
                        eventKey(column.tenantId(), column.columnId(), sourcePk),
                        Instant.now()
                )
        );
    }

    private VectorOutboxEventMeta createOutboxEvent(
            VectorColumnMeta column,
            Object pk,
            String pointId,
            long sourceVersion
    ) {
        Instant now = Instant.now();
        String sourcePk = pointIdNormalizer.relationalPkText(pk);
        String pkValueType = pointIdNormalizer.pkValueType(pk);
        long eventId = idGenerator.nextId();
        String eventKey = eventKey(column.tenantId(), column.columnId(), sourcePk);
        String dedupeKey = eventKey + ":" + eventId;
        return new VectorOutboxEventMeta(
                eventId,
                column.tenantId(),
                column.columnId(),
                eventKey,
                eventKey,
                "UPSERT",
                "UPDATE",
                "PENDING",
                sourcePk,
                pointId,
                pkValueType,
                dedupeKey,
                sourceVersion,
                "N",
                0,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
    }

    private String eventKey(String tenantId, long columnId, String sourcePk) {
        return tenantId + ":" + columnId + ":" + sourcePk;
    }

    private VectorCollectionMeta readyCollection(long columnId) {
        return vectorCollectionPort.findByColumnId(columnId)
                .stream()
                .filter(collection -> "ACTIVE".equals(collection.servingState())
                        && "READY".equals(collection.collectionStatus()))
                .findFirst()
                .orElseThrow(() -> new BizException("ready vector collection not found for column: " + columnId));
    }

    private PayloadValues payloadValues(VectorColumnMeta column, Map<String, Object> rawPayload) {
        Map<String, Object> payload = rawPayload == null ? Map.of() : rawPayload;
        List<VectorPayloadFieldMeta> writableFields = vectorPayloadFieldPort.findByColumnId(column.columnId())
                .stream()
                .filter(field -> "ACTIVE".equals(field.fieldStatus()))
                .toList();
        Map<String, VectorPayloadFieldMeta> fieldsByPayloadKey = writableFields.stream()
                .collect(Collectors.toMap(
                        VectorPayloadFieldMeta::payloadKey,
                        field -> field,
                        (left, right) -> {
                            throw new BizException("duplicated payloadKey in metadata: " + left.payloadKey());
                        },
                        LinkedHashMap::new
                ));

        for (String payloadKey : payload.keySet()) {
            if (!fieldsByPayloadKey.containsKey(payloadKey)) {
                throw new BizException("payload key is not registered or not active: " + payloadKey);
            }
        }

        Map<String, Object> scalarValues = new LinkedHashMap<>();
        Map<String, Object> qdrantPayload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            VectorPayloadFieldMeta field = fieldsByPayloadKey.get(entry.getKey());
            Object value = normalizeScalarValue(entry.getValue(), entry.getKey());
            String sourceColumnName = normalizeIdentifier(field.sourceColumnName(), "payload sourceColumnName", null);
            if (scalarValues.containsKey(sourceColumnName)) {
                throw new BizException("duplicated payload source column in metadata: " + sourceColumnName);
            }
            scalarValues.put(sourceColumnName, value);
            if ("Y".equals(field.syncEnabled())) {
                qdrantPayload.put(field.payloadKey(), value);
            }
        }
        return new PayloadValues(scalarValues, qdrantPayload);
    }

    private Object normalizeScalarValue(Object value, String payloadKey) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new BizException("payload value must be scalar for key: " + payloadKey);
    }

    private void validateNoColumnCollision(VectorColumnMeta column, Set<String> scalarColumns) {
        for (String scalarColumn : scalarColumns) {
            if (scalarColumn.equals(column.pkColumn())) {
                throw new BizException("payload source column must not equal pk column: " + scalarColumn);
            }
            if (scalarColumn.equals(column.vectorColumn())) {
                throw new BizException("payload source column must not equal vector column: " + scalarColumn);
            }
            if (scalarColumn.equals(ROW_VERSION_COLUMN)) {
                throw new BizException("payload source column must not equal row version column: " + scalarColumn);
            }
        }
    }

    private String writeTargetName(VectorCollectionMeta collection) {
        if (collection.aliasName() != null && !collection.aliasName().isBlank()) {
            return collection.aliasName();
        }
        return collection.collectionName();
    }

    private String optionalIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeIdentifier(value, fieldName, null);
    }

    private String normalizeIdentifier(String value, String fieldName, String defaultValue) {
        String normalized = normalizeWithDefault(value, defaultValue);
        if (normalized == null) {
            throw new BizException(fieldName + " must not be blank");
        }
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new BizException(fieldName + " must match [A-Za-z][A-Za-z0-9_]*");
        }
        return normalized;
    }

    private String normalizeWithDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private record PayloadValues(Map<String, Object> scalarValues, Map<String, Object> qdrantPayload) {
    }
}
