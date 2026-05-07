package com.krystal.vectorsidecarservice.application.system;

import com.krystal.vectorsidecarservice.application.port.in.CreateVectorTableUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorCollectionUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorIndexUseCase;
import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorEngineAdminPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorIndexPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CreateVectorTableService implements CreateVectorTableUseCase {

    private static final String DEFAULT_SCHEMA = "PUBLIC";
    private static final String DEFAULT_ENGINE_TYPE = "QDRANT";
    private static final String DEFAULT_TENANT = "DEFAULT";
    private static final String DEFAULT_ELEMENT_TYPE = "FLOAT32";
    private static final String DEFAULT_METRIC_TYPE = "COSINE";
    private static final String DEFAULT_SYNC_MODE = "FULL_AND_INCREMENTAL";
    private static final String DEFAULT_COLLECTION_VERSION = "v1";
    private static final String DEFAULT_INDEX_PROFILE = "default";
    private static final String DEFAULT_INDEX_BUILD_VERSION = "v1";
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final RelationalSchemaPort relationalSchemaPort;
    private final RegisterVectorColumnUseCase registerVectorColumnUseCase;
    private final RegisterVectorCollectionUseCase registerVectorCollectionUseCase;
    private final RegisterVectorIndexUseCase registerVectorIndexUseCase;
    private final VectorCollectionPort vectorCollectionPort;
    private final VectorIndexPort vectorIndexPort;
    private final VectorEngineAdminRouter vectorEngineAdminRouter;
    private final AltibaseCreateTableSqlBuilder sqlBuilder;
    private final TransactionTemplate transactionTemplate;

    @Override
    public CreateVectorTableResult create(CreateVectorTableCommand command) {
        if (command == null) {
            throw new BizException("request must not be null");
        }
        if (command.primaryKey() == null) {
            throw new BizException("primaryKey must not be null");
        }
        if (command.vectorColumn() == null) {
            throw new BizException("vectorColumn must not be null");
        }
        String engineType = normalizeEngineType(command.engineType());
        boolean autoRegisterCollection = Boolean.TRUE.equals(command.autoRegisterCollection());
        boolean autoRegisterIndex = Boolean.TRUE.equals(command.autoRegisterIndex());
        if (autoRegisterIndex && !autoRegisterCollection) {
            throw new BizException("autoRegisterIndex requires autoRegisterCollection=true");
        }

        String schemaName = normalizeIdentifier(command.schemaName(), "schemaName", DEFAULT_SCHEMA);
        String tableName = normalizeIdentifier(command.tableName(), "tableName", null);
        PrimaryKeySpec primaryKey = normalizePrimaryKey(command.primaryKey());
        List<ScalarColumnSpec> scalarColumns = normalizeScalarColumns(command.scalarColumns());
        VectorColumnSpec vectorColumn = normalizeVectorColumn(command.vectorColumn());

        ensureNoDuplicateColumns(primaryKey, scalarColumns, vectorColumn);

        String ddl = sqlBuilder.build(
                schemaName,
                tableName,
                primaryKey,
                scalarColumns,
                vectorColumn,
                relationalSchemaPort.databaseDialect()
        );

        boolean ifNotExists = command.ifNotExists() == null || command.ifNotExists();
        StageAResult stageA = transactionTemplate.execute(status -> runStageA(
                command,
                engineType,
                autoRegisterCollection,
                autoRegisterIndex,
                schemaName,
                tableName,
                primaryKey,
                vectorColumn,
                ddl,
                ifNotExists
        ));
        if (stageA == null) {
            throw new BizException("failed to initialize create table workflow");
        }

        if (stageA.collectionMeta() != null) {
            try {
                ProvisioningOutcome outcome = provisionPhysicalResources(stageA.collectionMeta(), stageA.indexMeta());
                transactionTemplate.executeWithoutResult(status ->
                        finalizeProvisioningStatus(stageA.collectionMeta().collectionId(), stageA.indexMeta(), outcome)
                );
            } catch (RuntimeException ex) {
                try {
                    transactionTemplate.executeWithoutResult(status ->
                            markProvisioningFailed(stageA.collectionMeta().collectionId(), stageA.indexMeta())
                    );
                } catch (RuntimeException statusEx) {
                    ex.addSuppressed(statusEx);
                }
                throw new BizException("failed to provision vector engine resources: " + ex.getMessage(), ex);
            }
        }

        return new CreateVectorTableResult(
                schemaName,
                tableName,
                vectorColumn.name(),
                vectorColumn.dimension(),
                vectorColumn.metricType(),
                stageA.columnMeta().columnId(),
                stageA.collectionMeta() == null ? null : stageA.collectionMeta().collectionId(),
                stageA.indexMeta() == null ? null : stageA.indexMeta().indexId(),
                stageA.indexProfileName(),
                stageA.ddlExecuted(),
                ddl
        );
    }

    private StageAResult runStageA(
            CreateVectorTableCommand command,
            String engineType,
            boolean autoRegisterCollection,
            boolean autoRegisterIndex,
            String schemaName,
            String tableName,
            PrimaryKeySpec primaryKey,
            VectorColumnSpec vectorColumn,
            String ddl,
            boolean ifNotExists
    ) {
        boolean ddlExecuted = true;
        if (ifNotExists && relationalSchemaPort.tableExists(schemaName, tableName)) {
            ddlExecuted = false;
        } else {
            relationalSchemaPort.executeDdl(ddl);
        }

        VectorColumnMeta columnMeta = registerVectorColumnUseCase.register(
                new RegisterVectorColumnUseCase.RegisterVectorColumnCommand(
                        command.tenantId(),
                        schemaName,
                        tableName,
                        primaryKey.name(),
                        vectorColumn.name(),
                        vectorColumn.dimension(),
                        vectorColumn.metricType(),
                        vectorColumn.syncMode()
                )
        );

        if (!autoRegisterCollection) {
            return new StageAResult(columnMeta, null, null, null, ddlExecuted);
        }

        VectorCollectionMeta collectionMeta = registerVectorCollectionUseCase.register(
                new RegisterVectorCollectionUseCase.RegisterVectorCollectionCommand(
                        columnMeta.columnId(),
                        engineType,
                        normalizeWithDefault(command.tenantId(), DEFAULT_TENANT),
                        defaultCollectionName(tableName, vectorColumn.name()),
                        defaultCollectionAlias(tableName, vectorColumn.name()),
                        DEFAULT_COLLECTION_VERSION,
                        "default",
                        vectorColumn.dimension(),
                        toCollectionDistanceMetric(vectorColumn.metricType()),
                        "UINT64",
                        "BUILDING",
                        "CREATING",
                        null,
                        null,
                        null,
                        "N",
                        null,
                        null,
                        null
                )
        );

        VectorIndexMeta indexMeta = null;
        String indexProfileName = null;
        if (autoRegisterIndex) {
            indexProfileName = normalizeProfileName(command.defaultIndexProfileName());
            indexMeta = registerVectorIndexUseCase.register(
                    new RegisterVectorIndexUseCase.RegisterVectorIndexCommand(
                            columnMeta.columnId(),
                            collectionMeta.collectionId(),
                            indexProfileName,
                            "HNSW",
                            vectorColumn.metricType(),
                            null,
                            null,
                            null,
                            "Y",
                            "OFFLINE",
                            "CREATING",
                            DEFAULT_INDEX_BUILD_VERSION
                    )
            );
        }
        return new StageAResult(columnMeta, collectionMeta, indexMeta, indexProfileName, ddlExecuted);
    }

    private void finalizeProvisioningStatus(Long collectionId, VectorIndexMeta indexMeta, ProvisioningOutcome outcome) {
        if (outcome.engineDisabled()) {
            vectorCollectionPort.updateStatus(collectionId, "BUILDING", "CREATING");
            if (indexMeta != null) {
                vectorIndexPort.updateStatus(indexMeta.indexId(), "OFFLINE", "CREATING");
            }
            return;
        }
        vectorCollectionPort.updateStatus(collectionId, "ACTIVE", "READY");
        if (indexMeta != null) {
            vectorIndexPort.updateStatus(indexMeta.indexId(), "ONLINE", "READY");
        }
    }

    private void markProvisioningFailed(Long collectionId, VectorIndexMeta indexMeta) {
        vectorCollectionPort.updateStatus(collectionId, "BUILDING", "FAILED");
        if (indexMeta != null) {
            vectorIndexPort.updateStatus(indexMeta.indexId(), "OFFLINE", "FAILED");
        }
    }

    private PrimaryKeySpec normalizePrimaryKey(PrimaryKeySpec primaryKey) {
        return new PrimaryKeySpec(
                normalizeIdentifier(primaryKey.name(), "primaryKey.name", null),
                normalizeScalarType(primaryKey.type(), "primaryKey.type")
        );
    }

    private List<ScalarColumnSpec> normalizeScalarColumns(List<ScalarColumnSpec> scalarColumns) {
        if (scalarColumns == null || scalarColumns.isEmpty()) {
            return List.of();
        }
        return scalarColumns.stream()
                .map(this::normalizeScalarColumn)
                .toList();
    }

    private ScalarColumnSpec normalizeScalarColumn(ScalarColumnSpec scalarColumn) {
        if (scalarColumn == null) {
            throw new BizException("scalarColumns item must not be null");
        }
        String name = normalizeIdentifier(scalarColumn.name(), "scalarColumns.name", null);
        String type = normalizeScalarType(scalarColumn.type(), "scalarColumns.type");
        Integer length = scalarColumn.length();
        if ((type.equals("VARCHAR") || type.equals("CHAR")) && (length == null || length <= 0)) {
            throw new BizException("scalarColumns.length must be greater than 0 for " + type);
        }
        if (!type.equals("VARCHAR") && !type.equals("CHAR") && length != null) {
            throw new BizException("scalarColumns.length is only allowed for VARCHAR/CHAR");
        }
        boolean nullable = scalarColumn.nullable() == null || scalarColumn.nullable();
        return new ScalarColumnSpec(name, type, length, nullable);
    }

    private VectorColumnSpec normalizeVectorColumn(VectorColumnSpec vectorColumn) {
        String name = normalizeIdentifier(vectorColumn.name(), "vectorColumn.name", null);
        if (vectorColumn.dimension() <= 0) {
            throw new BizException("vectorColumn.dimension must be greater than 0");
        }
        String elementType = normalizeVectorElementType(vectorColumn.elementType());
        String metricType = normalizeMetricType(vectorColumn.metricType());
        String syncMode = normalizeSyncMode(vectorColumn.syncMode());
        boolean nullable = vectorColumn.nullable() == null || vectorColumn.nullable();
        return new VectorColumnSpec(name, vectorColumn.dimension(), elementType, metricType, syncMode, nullable);
    }

    private String normalizeVectorElementType(String value) {
        String normalized = normalizeWithDefault(value, DEFAULT_ELEMENT_TYPE);
        if (!normalized.equals("FLOAT32") && !normalized.equals("FLOAT16") && !normalized.equals("INT8")) {
            throw new BizException("vectorColumn.elementType must be one of FLOAT32, FLOAT16, INT8");
        }
        return normalized;
    }

    private String normalizeMetricType(String value) {
        String normalized = normalizeWithDefault(value, DEFAULT_METRIC_TYPE);
        if (!normalized.equals("COSINE") && !normalized.equals("L2") && !normalized.equals("IP")) {
            throw new BizException("vectorColumn.metricType must be one of COSINE, L2, IP");
        }
        return normalized;
    }

    private String normalizeSyncMode(String value) {
        String normalized = normalizeWithDefault(value, DEFAULT_SYNC_MODE);
        if (!normalized.equals("FULL_AND_INCREMENTAL") && !normalized.equals("FULL_ONLY")) {
            throw new BizException("vectorColumn.syncMode must be one of FULL_AND_INCREMENTAL, FULL_ONLY");
        }
        return normalized;
    }

    private String normalizeScalarType(String value, String fieldName) {
        String normalized = normalizeWithDefault(value, null);
        if (normalized == null) {
            throw new BizException(fieldName + " must not be blank");
        }
        return switch (normalized) {
            case "BIGINT", "INTEGER", "INT", "VARCHAR", "CHAR", "DOUBLE", "FLOAT", "TIMESTAMP", "DATE" -> normalized;
            default -> throw new BizException(fieldName + " has unsupported type: " + normalized);
        };
    }

    private ProvisioningOutcome provisionPhysicalResources(VectorCollectionMeta collectionMeta, VectorIndexMeta indexMeta) {
        VectorEngineAdminPort adminPort = vectorEngineAdminRouter.get(collectionMeta.engineType());
        VectorEngineAdminPort.EnsureResult collectionResult = adminPort.ensureCollection(
                new VectorEngineAdminPort.EnsureCollectionCommand(
                        collectionMeta.collectionName(),
                        collectionMeta.vectorDim(),
                        collectionMeta.distanceMetric(),
                        collectionMeta.qdrantVectorName(),
                        collectionMeta.onDiskPayload(),
                        collectionMeta.hnswConfigJson(),
                        collectionMeta.quantizationConfigJson(),
                        collectionMeta.collectionConfigJson()
                )
        );
        if (collectionResult.isSkippedDisabled()) {
            return ProvisioningOutcome.engineDisabled(collectionResult.message());
        }

        if (collectionMeta.aliasName() != null && !collectionMeta.aliasName().isBlank()) {
            VectorEngineAdminPort.EnsureResult aliasResult = adminPort.ensureAlias(
                    new VectorEngineAdminPort.EnsureAliasCommand(
                            collectionMeta.aliasName(),
                            collectionMeta.collectionName()
                    )
            );
            if (aliasResult.isSkippedDisabled()) {
                return ProvisioningOutcome.engineDisabled(aliasResult.message());
            }
        }

        if (indexMeta != null) {
            VectorEngineAdminPort.EnsureResult indexResult = adminPort.ensureIndex(
                    new VectorEngineAdminPort.EnsureIndexCommand(
                            collectionMeta.collectionName(),
                            indexMeta.profileName(),
                            indexMeta.indexType(),
                            indexMeta.metricType(),
                            indexMeta.indexParamsJson(),
                            indexMeta.searchParamsJson(),
                            indexMeta.payloadIndexJson()
                    )
            );
            if (indexResult.isSkippedDisabled()) {
                return ProvisioningOutcome.engineDisabled(indexResult.message());
            }
        }
        return ProvisioningOutcome.ready();
    }

    private record ProvisioningOutcome(boolean engineDisabled, String message) {
        static ProvisioningOutcome ready() {
            return new ProvisioningOutcome(false, "ready");
        }

        static ProvisioningOutcome engineDisabled(String message) {
            return new ProvisioningOutcome(true, message);
        }
    }

    private record StageAResult(
            VectorColumnMeta columnMeta,
            VectorCollectionMeta collectionMeta,
            VectorIndexMeta indexMeta,
            String indexProfileName,
            boolean ddlExecuted
    ) {
    }

    private String toCollectionDistanceMetric(String metricType) {
        return switch (metricType) {
            case "COSINE" -> "COSINE";
            case "L2" -> "EUCLID";
            case "IP" -> "DOT";
            default -> throw new BizException("unsupported metricType for collection: " + metricType);
        };
    }

    private String normalizeProfileName(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_INDEX_PROFILE;
        }
        return value.trim();
    }

    private String normalizeEngineType(String value) {
        return normalizeWithDefault(value, DEFAULT_ENGINE_TYPE);
    }

    private String defaultCollectionName(String tableName, String vectorColumnName) {
        return tableName + "_" + vectorColumnName + "_V1";
    }

    private String defaultCollectionAlias(String tableName, String vectorColumnName) {
        return tableName + "_" + vectorColumnName + "_ACTIVE";
    }

    private void ensureNoDuplicateColumns(
            PrimaryKeySpec primaryKey,
            List<ScalarColumnSpec> scalarColumns,
            VectorColumnSpec vectorColumn
    ) {
        Set<String> names = new HashSet<>();
        checkDuplicate(names, primaryKey.name(), "primaryKey.name");
        for (ScalarColumnSpec scalarColumn : scalarColumns) {
            checkDuplicate(names, scalarColumn.name(), "scalarColumns.name");
        }
        checkDuplicate(names, vectorColumn.name(), "vectorColumn.name");
    }

    private void checkDuplicate(Set<String> names, String columnName, String fieldName) {
        if (!names.add(columnName)) {
            throw new BizException(fieldName + " has duplicated column name: " + columnName);
        }
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
}
