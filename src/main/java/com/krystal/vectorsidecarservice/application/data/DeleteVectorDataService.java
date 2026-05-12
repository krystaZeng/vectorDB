package com.krystal.vectorsidecarservice.application.data;

import com.krystal.vectorsidecarservice.application.port.in.DeleteVectorDataUseCase;
import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorDataRelationalPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorOutboxEventPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorSourceVersionPort;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorColumnLifecycle;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DeleteVectorDataService implements DeleteVectorDataUseCase {

    private static final String DEFAULT_TENANT = "DEFAULT";
    private static final String DEFAULT_SCHEMA = "PUBLIC";
    private static final String ROW_VERSION_COLUMN = "ROW_VERSION";
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final VectorMetadataPort vectorMetadataPort;
    private final VectorCollectionPort vectorCollectionPort;
    private final RelationalSchemaPort relationalSchemaPort;
    private final VectorDataRelationalPort vectorDataRelationalPort;
    private final VectorOutboxEventPort vectorOutboxEventPort;
    private final VectorSourceVersionPort vectorSourceVersionPort;
    private final IdGeneratorPort idGenerator;
    private final VectorPointIdNormalizer pointIdNormalizer;

    @Override
    @Transactional
    public DeleteVectorDataResult delete(DeleteVectorDataCommand command) {
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

        VectorColumnMeta column = resolveColumn(tenantId, schemaName, tableName, vectorColumnName);
        requireColumnDeletable(column);
        VectorCollectionMeta collection = readyCollection(column.columnId());
        String rowVersionColumn = relationalSchemaPort.columnExists(column.schemaName(), column.tableName(), ROW_VERSION_COLUMN)
                ? ROW_VERSION_COLUMN
                : null;
        if (!rowExists(column, command.pk(), rowVersionColumn)) {
            throw new BizException("row not found for delete: " + pointIdNormalizer.relationalPkText(command.pk()));
        }

        long sourceVersion = nextSourceVersion(column, command.pk());
        VectorPointIdNormalizer.NormalizedPointId pointId = pointIdNormalizer.normalize(command.pk(), collection.qdrantIdType());
        String writeTargetName = writeTargetName(collection);

        VectorOutboxEventMeta outboxEvent = createOutboxEvent(column, command.pk(), pointId.text(), sourceVersion);
        VectorOutboxEventPort.SaveResult outboxSaveResult = vectorOutboxEventPort.enqueueOrMergeActive(outboxEvent);
        VectorOutboxEventMeta persistedOutboxEvent = outboxSaveResult.event();
        if (!outboxSaveResult.created() && "DEAD".equals(persistedOutboxEvent.eventStatus())) {
            throw new BizException("existing qdrant sync event is DEAD; manual retry required: "
                    + persistedOutboxEvent.eventId());
        }

        int deletedRows = vectorDataRelationalPort.delete(
                new VectorDataRelationalPort.DeleteRowCommand(
                        column.schemaName(),
                        column.tableName(),
                        column.pkColumn(),
                        command.pk()
                )
        );
        if (deletedRows != 1) {
            throw new BizException("row not found for delete: " + pointIdNormalizer.relationalPkText(command.pk()));
        }

        return new DeleteVectorDataResult(
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
                        ? "qdrant vector delete event enqueued from delete: " + persistedOutboxEvent.eventId()
                        : "qdrant vector delete event merged from delete: " + persistedOutboxEvent.eventId()
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

    private void requireColumnDeletable(VectorColumnMeta column) {
        if (!VectorColumnLifecycle.ACTIVE.status().equals(column.status())) {
            throw new BizException("vector column is not ACTIVE: " + column.columnId());
        }
        if (!relationalSchemaPort.tableExists(column.schemaName(), column.tableName())) {
            throw new BizException("table does not exist: " + column.schemaName() + "." + column.tableName());
        }
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
                "DELETE",
                "DELETE",
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

    private VectorCollectionMeta readyCollection(long columnId) {
        return vectorCollectionPort.findByColumnId(columnId)
                .stream()
                .filter(collection -> "ACTIVE".equals(collection.servingState())
                        && "READY".equals(collection.collectionStatus()))
                .findFirst()
                .orElseThrow(() -> new BizException("ready vector collection not found for column: " + columnId));
    }

    private String writeTargetName(VectorCollectionMeta collection) {
        if (collection.aliasName() != null && !collection.aliasName().isBlank()) {
            return collection.aliasName();
        }
        return collection.collectionName();
    }

    private String eventKey(String tenantId, long columnId, String sourcePk) {
        return tenantId + ":" + columnId + ":" + sourcePk;
    }

    private String normalizeWithDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String optionalIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeIdentifier(value, fieldName, null);
    }

    private String normalizeIdentifier(String value, String fieldName, String defaultValue) {
        if (value == null || value.isBlank()) {
            if (defaultValue != null) {
                return defaultValue;
            }
            throw new BizException(fieldName + " must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new BizException(fieldName + " must match [A-Za-z][A-Za-z0-9_]*");
        }
        return normalized;
    }
}
