package com.krystal.vectorsidecarservice.application.system;

import com.krystal.vectorsidecarservice.application.port.in.CreateVectorTableUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorCollectionUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorIndexUseCase;
import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorEngineAdminPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorColumnLifecycle;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorColumnLifecycleService;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorCollectionLifecycle;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorCollectionLifecycleService;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorIndexLifecycle;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorIndexLifecycleService;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
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
    private static final int MAX_REMARK_LEN = 1024;
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final RelationalSchemaPort relationalSchemaPort;
    private final VectorMetadataPort vectorMetadataPort;
    private final RegisterVectorColumnUseCase registerVectorColumnUseCase;
    private final RegisterVectorCollectionUseCase registerVectorCollectionUseCase;
    private final RegisterVectorIndexUseCase registerVectorIndexUseCase;
    private final VectorColumnLifecycleService vectorColumnLifecycleService;
    private final VectorCollectionLifecycleService vectorCollectionLifecycleService;
    private final VectorIndexLifecycleService vectorIndexLifecycleService;
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
        String definitionHash = definitionHash(
                command,
                engineType,
                autoRegisterCollection,
                autoRegisterIndex,
                schemaName,
                tableName,
                primaryKey,
                scalarColumns,
                vectorColumn,
                ddl
        );

        boolean ifNotExists = command.ifNotExists() == null || command.ifNotExists();
        StageAResult stageA = transactionTemplate.execute(status -> prepareMetadata(
                command,
                engineType,
                autoRegisterCollection,
                autoRegisterIndex,
                schemaName,
                tableName,
                primaryKey,
                vectorColumn,
                definitionHash
        ));
        if (stageA == null) {
            throw new BizException("failed to initialize create table workflow");
        }
        if (stageA.workflowReady()) {
            validateRelationalTable(schemaName, tableName, primaryKey, scalarColumns, vectorColumn);
            return createResult(schemaName, tableName, vectorColumn, stageA, DdlResult.ALREADY_EXISTS_AND_MATCHED, ddl);
        }

        DdlResult ddlResult;
        try {
            ddlResult = ensureRelationalTable(
                    schemaName,
                    tableName,
                    primaryKey,
                    scalarColumns,
                    vectorColumn,
                    ddl,
                    ifNotExists || stageA.metadataExisted()
            );
        } catch (RuntimeException ex) {
            markWorkflowFailed(stageA.columnMeta().columnId(), stageA.collectionMeta(), stageA.indexMeta(), ex);
            throw ex;
        }

        if (stageA.collectionMeta() != null) {
            try {
                ProvisioningOutcome outcome = provisionPhysicalResources(stageA.collectionMeta(), stageA.indexMeta());
                transactionTemplate.executeWithoutResult(status ->
                        finalizeProvisioningStatus(stageA.columnMeta().columnId(), stageA.collectionMeta(), stageA.indexMeta(), outcome)
                );
            } catch (RuntimeException ex) {
                markWorkflowFailed(stageA.columnMeta().columnId(), stageA.collectionMeta(), stageA.indexMeta(), ex);
                throw new BizException("failed to provision vector engine resources: " + ex.getMessage(), ex);
            }
        } else {
            transactionTemplate.executeWithoutResult(status -> markColumnActive(stageA.columnMeta().columnId()));
        }

        return createResult(schemaName, tableName, vectorColumn, stageA, ddlResult, ddl);
    }

    private CreateVectorTableResult createResult(
            String schemaName,
            String tableName,
            VectorColumnSpec vectorColumn,
            StageAResult stageA,
            DdlResult ddlResult,
            String ddl
    ) {
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
                ddlResult.createdByThisAttempt(),
                ddl
        );
    }

    private StageAResult prepareMetadata(
            CreateVectorTableCommand command,
            String engineType,
            boolean autoRegisterCollection,
            boolean autoRegisterIndex,
            String schemaName,
            String tableName,
            PrimaryKeySpec primaryKey,
            VectorColumnSpec vectorColumn,
            String definitionHash
    ) {
        String tenantId = normalizeWithDefault(command.tenantId(), DEFAULT_TENANT);
        Optional<VectorColumnMeta> existing = vectorMetadataPort.findByIdentity(
                tenantId,
                schemaName,
                tableName,
                vectorColumn.name()
        );
        ColumnResolution columnResolution = existing
                .map(meta -> new ColumnResolution(resolveExistingColumn(meta, definitionHash), true))
                .orElseGet(() -> registerOrLoadBuildingColumn(
                        command,
                        tenantId,
                        schemaName,
                        tableName,
                        primaryKey,
                        vectorColumn,
                        definitionHash
                ));
        VectorColumnMeta columnMeta = columnResolution.columnMeta();
        boolean metadataExisted = columnResolution.metadataExisted();

        if (!autoRegisterCollection) {
            return new StageAResult(
                    columnMeta,
                    null,
                    null,
                    null,
                    metadataExisted,
                    isWorkflowReady(columnMeta, null, null, false, false)
            );
        }

        VectorCollectionMeta collectionMeta = existingCollection(columnMeta.columnId())
                .orElseGet(() -> registerCreatingCollection(command, engineType, schemaName, tableName, vectorColumn, columnMeta));

        VectorIndexMeta indexMeta = null;
        String indexProfileName = null;
        if (autoRegisterIndex) {
            indexProfileName = normalizeProfileName(command.defaultIndexProfileName());
            String finalIndexProfileName = indexProfileName;
            indexMeta = existingIndex(columnMeta.columnId(), finalIndexProfileName)
                    .orElseGet(() -> registerCreatingIndex(vectorColumn, columnMeta, collectionMeta, finalIndexProfileName));
        }
        return new StageAResult(
                columnMeta,
                collectionMeta,
                indexMeta,
                indexProfileName,
                metadataExisted,
                isWorkflowReady(columnMeta, collectionMeta, indexMeta, autoRegisterCollection, autoRegisterIndex)
        );
    }

    private boolean isWorkflowReady(
            VectorColumnMeta columnMeta,
            VectorCollectionMeta collectionMeta,
            VectorIndexMeta indexMeta,
            boolean collectionRequired,
            boolean indexRequired
    ) {
        if (!VectorColumnLifecycle.ACTIVE.status().equals(columnMeta.status())) {
            return false;
        }
        if (!collectionRequired) {
            return true;
        }
        if (collectionMeta == null || !"ACTIVE".equals(collectionMeta.servingState())
                || !"READY".equals(collectionMeta.collectionStatus())) {
            return false;
        }
        if (!indexRequired) {
            return true;
        }
        return indexMeta != null
                && "ONLINE".equals(indexMeta.servingState())
                && "READY".equals(indexMeta.indexStatus());
    }

    private VectorColumnMeta resolveExistingColumn(VectorColumnMeta existing, String definitionHash) {
        if (!definitionHash.equals(existing.definitionHash())) {
            throw new BizException("vector column definition conflicts with existing metadata: "
                    + existing.schemaName() + "." + existing.tableName() + "." + existing.vectorColumn());
        }
        return switch (VectorColumnLifecycle.normalize(existing.status())) {
            case BUILDING, ACTIVE -> existing;
            case FAILED -> throw new BizException("vector column is FAILED; use explicit retry/repair: "
                    + existing.schemaName() + "." + existing.tableName() + "." + existing.vectorColumn());
            case DISABLED -> throw new BizException("vector column is DISABLED: "
                    + existing.schemaName() + "." + existing.tableName() + "." + existing.vectorColumn());
        };
    }

    private ColumnResolution registerOrLoadBuildingColumn(
            CreateVectorTableCommand command,
            String tenantId,
            String schemaName,
            String tableName,
            PrimaryKeySpec primaryKey,
            VectorColumnSpec vectorColumn,
            String definitionHash
    ) {
        try {
            return new ColumnResolution(
                    registerBuildingColumn(command, schemaName, tableName, primaryKey, vectorColumn, definitionHash),
                    false
            );
        } catch (BizException ex) {
            Optional<VectorColumnMeta> racedMetadata = vectorMetadataPort.findByIdentity(
                    tenantId,
                    schemaName,
                    tableName,
                    vectorColumn.name()
            );
            if (racedMetadata.isEmpty()) {
                throw ex;
            }
            log.info(
                    "Vector column metadata already exists after concurrent registration; resolving as retry: {}.{}.{}",
                    schemaName,
                    tableName,
                    vectorColumn.name()
            );
            return new ColumnResolution(resolveExistingColumn(racedMetadata.get(), definitionHash), true);
        }
    }

    private VectorColumnMeta registerBuildingColumn(
            CreateVectorTableCommand command,
            String schemaName,
            String tableName,
            PrimaryKeySpec primaryKey,
            VectorColumnSpec vectorColumn,
            String definitionHash
    ) {
        return registerVectorColumnUseCase.register(
                new RegisterVectorColumnUseCase.RegisterVectorColumnCommand(
                        command.tenantId(),
                        schemaName,
                        tableName,
                        primaryKey.name(),
                        vectorColumn.name(),
                        vectorColumn.dimension(),
                        vectorColumn.metricType(),
                        vectorEncoding(vectorColumn.elementType()),
                        vectorColumn.syncMode(),
                        VectorColumnLifecycle.BUILDING.status(),
                        definitionHash,
                        null
                )
        );
    }

    private Optional<VectorCollectionMeta> existingCollection(long columnId) {
        return registerVectorCollectionUseCase.listByColumnId(columnId)
                .stream()
                .filter(meta -> DEFAULT_COLLECTION_VERSION.equals(meta.collectionVersion()))
                .findFirst();
    }

    private VectorCollectionMeta registerCreatingCollection(
            CreateVectorTableCommand command,
            String engineType,
            String schemaName,
            String tableName,
            VectorColumnSpec vectorColumn,
            VectorColumnMeta columnMeta
    ) {
        return registerVectorCollectionUseCase.register(
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
                        VectorCollectionLifecycle.CREATING.servingState(),
                        VectorCollectionLifecycle.CREATING.collectionStatus(),
                        null,
                        null,
                        null,
                        "N",
                        null,
                        null,
                        null
                )
        );
    }

    private Optional<VectorIndexMeta> existingIndex(long columnId, String profileName) {
        return registerVectorIndexUseCase.listByColumnId(columnId)
                .stream()
                .filter(meta -> profileName.equals(meta.profileName()))
                .findFirst();
    }

    private VectorIndexMeta registerCreatingIndex(
            VectorColumnSpec vectorColumn,
            VectorColumnMeta columnMeta,
            VectorCollectionMeta collectionMeta,
            String indexProfileName
    ) {
        return registerVectorIndexUseCase.register(
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
                        VectorIndexLifecycle.CREATING.servingState(),
                        VectorIndexLifecycle.CREATING.indexStatus(),
                        DEFAULT_INDEX_BUILD_VERSION
                )
        );
    }

    private DdlResult ensureRelationalTable(
            String schemaName,
            String tableName,
            PrimaryKeySpec primaryKey,
            List<ScalarColumnSpec> scalarColumns,
            VectorColumnSpec vectorColumn,
            String ddl,
            boolean ifNotExists
    ) {
        if (relationalSchemaPort.tableExists(schemaName, tableName)) {
            if (!ifNotExists) {
                throw new BizException("table already exists: " + schemaName + "." + tableName);
            }
            validateRelationalTable(schemaName, tableName, primaryKey, scalarColumns, vectorColumn);
            return DdlResult.ALREADY_EXISTS_AND_MATCHED;
        }
        try {
            relationalSchemaPort.executeDdl(ddl);
            return DdlResult.CREATED_BY_THIS_ATTEMPT;
        } catch (RelationalSchemaPort.DdlExecutionException ex) {
            return recoverAfterDdlFailure(
                    schemaName,
                    tableName,
                    primaryKey,
                    scalarColumns,
                    vectorColumn,
                    ifNotExists,
                    ex
            );
        }
    }

    private DdlResult recoverAfterDdlFailure(
            String schemaName,
            String tableName,
            PrimaryKeySpec primaryKey,
            List<ScalarColumnSpec> scalarColumns,
            VectorColumnSpec vectorColumn,
            boolean ifNotExists,
            RelationalSchemaPort.DdlExecutionException ex
    ) {
        if (!ifNotExists || !canRecoverByCheckingTable(ex.failureKind())) {
            log.warn(
                    "DDL failed for {}.{} with kind {}; not treating it as idempotent",
                    schemaName,
                    tableName,
                    ex.failureKind(),
                    ex
            );
            throw ex;
        }
        log.warn(
                "DDL failed for {}.{} with kind {}; rechecking table definition before idempotent recovery",
                schemaName,
                tableName,
                ex.failureKind(),
                ex
        );
        if (!relationalSchemaPort.tableExists(schemaName, tableName)) {
            throw ex;
        }
        validateRelationalTable(schemaName, tableName, primaryKey, scalarColumns, vectorColumn);
        log.info("DDL target table already exists and matches expected definition: {}.{}", schemaName, tableName);
        return DdlResult.ALREADY_EXISTS_AND_MATCHED;
    }

    private boolean canRecoverByCheckingTable(RelationalSchemaPort.DdlFailureKind failureKind) {
        return failureKind == RelationalSchemaPort.DdlFailureKind.OBJECT_ALREADY_EXISTS
                || failureKind == RelationalSchemaPort.DdlFailureKind.UNKNOWN_EXECUTION_STATE;
    }

    private void validateRelationalTable(
            String schemaName,
            String tableName,
            PrimaryKeySpec primaryKey,
            List<ScalarColumnSpec> scalarColumns,
            VectorColumnSpec vectorColumn
    ) {
        relationalSchemaPort.validateTableDefinition(tableDefinition(schemaName, tableName, primaryKey, scalarColumns, vectorColumn));
    }

    private RelationalSchemaPort.TableDefinition tableDefinition(
            String schemaName,
            String tableName,
            PrimaryKeySpec primaryKey,
            List<ScalarColumnSpec> scalarColumns,
            VectorColumnSpec vectorColumn
    ) {
        List<RelationalSchemaPort.ColumnDefinition> columns = new ArrayList<>();
        columns.add(new RelationalSchemaPort.ColumnDefinition(primaryKey.name(), scalarType(primaryKey.type()), scalarLength(primaryKey.type(), null), false));
        for (ScalarColumnSpec scalarColumn : scalarColumns) {
            columns.add(new RelationalSchemaPort.ColumnDefinition(
                    scalarColumn.name(),
                    scalarType(scalarColumn.type()),
                    scalarLength(scalarColumn.type(), scalarColumn.length()),
                    scalarColumn.nullable()
            ));
        }
        columns.add(new RelationalSchemaPort.ColumnDefinition(
                vectorColumn.name(),
                vectorStorageType(relationalSchemaPort.databaseDialect()),
                vectorStorageLength(vectorColumn),
                vectorColumn.nullable()
        ));
        return new RelationalSchemaPort.TableDefinition(schemaName, tableName, primaryKey.name(), columns);
    }

    private void finalizeProvisioningStatus(
            long columnId,
            VectorCollectionMeta collectionMeta,
            VectorIndexMeta indexMeta,
            ProvisioningOutcome outcome
    ) {
        if (outcome.engineDisabled()) {
            vectorCollectionLifecycleService.markCreating(collectionMeta.collectionId());
            if (indexMeta != null) {
                vectorIndexLifecycleService.markCreating(indexMeta.indexId());
            }
            return;
        }
        vectorCollectionLifecycleService.markReady(collectionMeta.collectionId());
        if (indexMeta != null) {
            vectorIndexLifecycleService.markReady(indexMeta.indexId());
        }
        markColumnActive(columnId);
    }

    private void markWorkflowFailed(long columnId, VectorCollectionMeta collectionMeta, VectorIndexMeta indexMeta, RuntimeException ex) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                vectorColumnLifecycleService.markFailed(columnId, truncateRemark(ex.getMessage()));
                if (collectionMeta != null) {
                    markCollectionFailedIfUnready(collectionMeta);
                }
                if (indexMeta != null) {
                    markIndexFailedIfUnready(indexMeta);
                }
            });
        } catch (RuntimeException statusEx) {
            ex.addSuppressed(statusEx);
        }
    }

    private void markCollectionFailedIfUnready(VectorCollectionMeta collectionMeta) {
        VectorCollectionLifecycle lifecycle = VectorCollectionLifecycle.fromPersisted(
                collectionMeta.servingState(),
                collectionMeta.collectionStatus()
        );
        if (lifecycle == VectorCollectionLifecycle.READY
                || lifecycle == VectorCollectionLifecycle.DEPRECATED
                || lifecycle == VectorCollectionLifecycle.DROPPED) {
            return;
        }
        vectorCollectionLifecycleService.markFailed(collectionMeta.collectionId());
    }

    private void markIndexFailedIfUnready(VectorIndexMeta indexMeta) {
        VectorIndexLifecycle lifecycle = VectorIndexLifecycle.fromPersisted(
                indexMeta.servingState(),
                indexMeta.indexStatus()
        );
        if (lifecycle == VectorIndexLifecycle.READY
                || lifecycle == VectorIndexLifecycle.OFFLINE_READY
                || lifecycle == VectorIndexLifecycle.CANARY_READY) {
            return;
        }
        vectorIndexLifecycleService.markFailed(indexMeta.indexId());
    }

    private void markColumnActive(long columnId) {
        vectorColumnLifecycleService.markActive(columnId);
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

    private String scalarType(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "INT" -> "INTEGER";
            default -> value.toUpperCase(Locale.ROOT);
        };
    }

    private Integer scalarLength(String type, Integer length) {
        String normalized = type.toUpperCase(Locale.ROOT);
        return normalized.equals("VARCHAR") || normalized.equals("CHAR") ? length : null;
    }

    private String vectorStorageType(RelationalSchemaPort.DatabaseDialect dialect) {
        return dialect == RelationalSchemaPort.DatabaseDialect.ALTIBASE ? "VARBYTE" : "VARBINARY";
    }

    private String vectorEncoding(String elementType) {
        return switch (elementType.toUpperCase(Locale.ROOT)) {
            case "FLOAT32" -> "FLOAT32_LE";
            case "FLOAT16" -> "FLOAT16_LE";
            case "INT8" -> "INT8";
            default -> throw new BizException("vectorColumn.elementType must be one of FLOAT32, FLOAT16, INT8");
        };
    }

    private int vectorStorageLength(VectorColumnSpec vectorColumn) {
        int bytesPerElement = switch (vectorColumn.elementType().toUpperCase(Locale.ROOT)) {
            case "FLOAT32" -> 4;
            case "FLOAT16" -> 2;
            case "INT8" -> 1;
            default -> throw new BizException("vectorColumn.elementType must be one of FLOAT32, FLOAT16, INT8");
        };
        long storageLength = (long) vectorColumn.dimension() * bytesPerElement;
        if (storageLength > Integer.MAX_VALUE) {
            throw new BizException("vector storage length is too large");
        }
        return (int) storageLength;
    }

    private String definitionHash(
            CreateVectorTableCommand command,
            String engineType,
            boolean autoRegisterCollection,
            boolean autoRegisterIndex,
            String schemaName,
            String tableName,
            PrimaryKeySpec primaryKey,
            List<ScalarColumnSpec> scalarColumns,
            VectorColumnSpec vectorColumn,
            String ddl
    ) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("tenant=").append(normalizeWithDefault(command.tenantId(), DEFAULT_TENANT)).append('\n');
        canonical.append("schema=").append(schemaName).append('\n');
        canonical.append("table=").append(tableName).append('\n');
        canonical.append("engine=").append(engineType).append('\n');
        canonical.append("autoCollection=").append(autoRegisterCollection).append('\n');
        canonical.append("autoIndex=").append(autoRegisterIndex).append('\n');
        canonical.append("indexProfile=").append(normalizeProfileName(command.defaultIndexProfileName())).append('\n');
        canonical.append("pk=").append(primaryKey.name()).append(':').append(primaryKey.type()).append('\n');
        for (ScalarColumnSpec scalarColumn : scalarColumns) {
            canonical.append("scalar=")
                    .append(scalarColumn.name()).append(':')
                    .append(scalarColumn.type()).append(':')
                    .append(scalarColumn.length()).append(':')
                    .append(scalarColumn.nullable())
                    .append('\n');
        }
        canonical.append("vector=")
                .append(vectorColumn.name()).append(':')
                .append(vectorColumn.dimension()).append(':')
                .append(vectorColumn.elementType()).append(':')
                .append(vectorColumn.metricType()).append(':')
                .append(vectorColumn.syncMode()).append(':')
                .append(vectorColumn.nullable())
                .append('\n');
        canonical.append("ddl=").append(ddl);
        return sha256(canonical.toString());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String truncateRemark(String remark) {
        if (remark == null || remark.isBlank()) {
            return "workflow failed";
        }
        String normalized = remark.trim();
        return normalized.length() <= MAX_REMARK_LEN ? normalized : normalized.substring(0, MAX_REMARK_LEN);
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
            boolean metadataExisted,
            boolean workflowReady
    ) {
    }

    private record ColumnResolution(VectorColumnMeta columnMeta, boolean metadataExisted) {
    }

    private enum DdlResult {
        CREATED_BY_THIS_ATTEMPT,
        ALREADY_EXISTS_AND_MATCHED;

        boolean createdByThisAttempt() {
            return this == CREATED_BY_THIS_ATTEMPT;
        }
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
