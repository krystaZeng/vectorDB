package com.krystal.vectorsidecarservice.application.system;

import com.krystal.vectorsidecarservice.application.port.in.CreateSimpleVectorTableUseCase;
import com.krystal.vectorsidecarservice.application.port.in.CreateVectorTableUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorPayloadFieldUseCase;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorPayloadFieldMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CreateSimpleVectorTableService implements CreateSimpleVectorTableUseCase {

    private static final String DEFAULT_ENGINE_TYPE = "QDRANT";
    private static final String DEFAULT_INDEX_PROFILE = "default";
    private static final String DEFAULT_ELEMENT_TYPE = "FLOAT32";
    private static final String DEFAULT_METRIC_TYPE = "COSINE";
    private static final String DEFAULT_SYNC_MODE = "FULL_AND_INCREMENTAL";

    private final CreateVectorTableUseCase createVectorTableUseCase;
    private final RegisterVectorPayloadFieldUseCase registerVectorPayloadFieldUseCase;

    @Override
    public CreateSimpleVectorTableResult create(CreateSimpleVectorTableCommand command) {
        if (command == null) {
            throw new BizException("request must not be null");
        }
        if (command.primaryKey() == null) {
            throw new BizException("primaryKey must not be null");
        }
        if (command.vectorColumn() == null) {
            throw new BizException("vectorColumn must not be null");
        }

        CreateVectorTableUseCase.CreateVectorTableResult tableResult = createVectorTableUseCase.create(
                new CreateVectorTableUseCase.CreateVectorTableCommand(
                        command.tenantId(),
                        command.schemaName(),
                        command.tableName(),
                        DEFAULT_ENGINE_TYPE,
                        true,
                        true,
                        true,
                        DEFAULT_INDEX_PROFILE,
                        new CreateVectorTableUseCase.PrimaryKeySpec(
                                command.primaryKey().name(),
                                command.primaryKey().type()
                        ),
                        scalarColumns(command.scalarColumns()),
                        new CreateVectorTableUseCase.VectorColumnSpec(
                                command.vectorColumn().name(),
                                command.vectorColumn().dimension(),
                                DEFAULT_ELEMENT_TYPE,
                                DEFAULT_METRIC_TYPE,
                                DEFAULT_SYNC_MODE,
                                command.vectorColumn().nullable()
                        )
                )
        );

        List<PayloadFieldResult> payloadFields = registerPayloadFields(
                tableResult.columnId(),
                command.scalarColumns()
        );
        return new CreateSimpleVectorTableResult(
                tableResult.schemaName(),
                tableResult.tableName(),
                tableResult.vectorColumn(),
                tableResult.dimension(),
                tableResult.metricType(),
                tableResult.columnId(),
                tableResult.collectionId(),
                tableResult.indexId(),
                tableResult.indexProfileName(),
                tableResult.ddlExecuted(),
                payloadFields
        );
    }

    private List<CreateVectorTableUseCase.ScalarColumnSpec> scalarColumns(List<ScalarColumnSpec> scalarColumns) {
        if (scalarColumns == null || scalarColumns.isEmpty()) {
            return List.of();
        }
        return scalarColumns.stream()
                .map(column -> new CreateVectorTableUseCase.ScalarColumnSpec(
                        column.name(),
                        column.type(),
                        column.length(),
                        column.nullable()
                ))
                .toList();
    }

    private List<PayloadFieldResult> registerPayloadFields(long columnId, List<ScalarColumnSpec> scalarColumns) {
        if (scalarColumns == null || scalarColumns.isEmpty()) {
            return List.of();
        }
        Set<String> payloadKeys = new HashSet<>();
        return scalarColumns.stream()
                .filter(column -> hasText(column.payloadKey()))
                .map(column -> {
                    String payloadKey = column.payloadKey().trim();
                    if (!payloadKeys.add(payloadKey)) {
                        throw new BizException("duplicated payloadKey: " + column.payloadKey());
                    }
                    return registerOrLoadPayloadField(columnId, column);
                })
                .toList();
    }

    private PayloadFieldResult registerOrLoadPayloadField(long columnId, ScalarColumnSpec scalarColumn) {
        String payloadKey = scalarColumn.payloadKey().trim();
        String fieldType = payloadFieldType(scalarColumn);
        String syncEnabled = flag(scalarColumn.payloadSyncEnabled(), true);
        Optional<VectorPayloadFieldMeta> existing = registerVectorPayloadFieldUseCase.listByColumnId(columnId)
                .stream()
                .filter(field -> payloadKey.equals(field.payloadKey()))
                .findFirst();
        if (existing.isPresent()) {
            return fromExisting(scalarColumn, fieldType, syncEnabled, existing.get());
        }
        VectorPayloadFieldMeta created = registerVectorPayloadFieldUseCase.register(
                new RegisterVectorPayloadFieldUseCase.RegisterVectorPayloadFieldCommand(
                        columnId,
                        scalarColumn.name(),
                        payloadKey,
                        fieldType,
                        "Y",
                        "Y",
                        "Y",
                        syncEnabled,
                        "ACTIVE",
                        null
                )
        );
        return payloadFieldResult(created);
    }

    private PayloadFieldResult fromExisting(
            ScalarColumnSpec scalarColumn,
            String fieldType,
            String syncEnabled,
            VectorPayloadFieldMeta existing
    ) {
        if (!equalsIgnoreCase(existing.sourceColumnName(), scalarColumn.name())) {
            throw new BizException("payload field source column conflicts for payloadKey: " + existing.payloadKey());
        }
        if (!fieldType.equalsIgnoreCase(existing.fieldType())) {
            throw new BizException("payload field type conflicts for payloadKey: " + existing.payloadKey());
        }
        if (!syncEnabled.equalsIgnoreCase(existing.syncEnabled())) {
            throw new BizException("payload field syncEnabled conflicts for payloadKey: " + existing.payloadKey());
        }
        if (!"ACTIVE".equals(existing.fieldStatus())) {
            throw new BizException("payload field is not ACTIVE: " + existing.payloadKey());
        }
        return payloadFieldResult(existing);
    }

    private PayloadFieldResult payloadFieldResult(VectorPayloadFieldMeta meta) {
        return new PayloadFieldResult(
                meta.fieldId(),
                meta.sourceColumnName(),
                meta.payloadKey(),
                meta.fieldType(),
                meta.syncEnabled(),
                meta.fieldStatus()
        );
    }

    private String payloadFieldType(ScalarColumnSpec scalarColumn) {
        if (hasText(scalarColumn.payloadFieldType())) {
            return scalarColumn.payloadFieldType().trim().toUpperCase(Locale.ROOT);
        }
        String type = scalarColumn.type() == null ? "" : scalarColumn.type().trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "BIGINT", "INTEGER", "INT" -> "INTEGER";
            case "DOUBLE", "FLOAT" -> "FLOAT";
            case "TIMESTAMP", "DATE" -> "DATETIME";
            default -> "KEYWORD";
        };
    }

    private String flag(Boolean value, boolean defaultValue) {
        boolean normalized = value == null ? defaultValue : value;
        return normalized ? "Y" : "N";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return Objects.equals(
                left == null ? null : left.trim().toUpperCase(Locale.ROOT),
                right == null ? null : right.trim().toUpperCase(Locale.ROOT)
        );
    }
}
