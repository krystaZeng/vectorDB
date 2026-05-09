package com.krystal.vectorsidecarservice.application.port.in;

import java.util.List;

public interface CreateSimpleVectorTableUseCase {

    CreateSimpleVectorTableResult create(CreateSimpleVectorTableCommand command);

    record CreateSimpleVectorTableCommand(
            String tenantId,
            String schemaName,
            String tableName,
            PrimaryKeySpec primaryKey,
            List<ScalarColumnSpec> scalarColumns,
            VectorColumnSpec vectorColumn
    ) {
    }

    record PrimaryKeySpec(
            String name,
            String type
    ) {
    }

    record ScalarColumnSpec(
            String name,
            String type,
            Integer length,
            Boolean nullable,
            String payloadKey,
            Boolean payloadSyncEnabled,
            String payloadFieldType
    ) {
    }

    record VectorColumnSpec(
            String name,
            int dimension,
            Boolean nullable
    ) {
    }

    record CreateSimpleVectorTableResult(
            String schemaName,
            String tableName,
            String vectorColumn,
            Integer dimension,
            String metricType,
            Long columnId,
            Long collectionId,
            Long indexId,
            String indexProfileName,
            boolean ddlExecuted,
            List<PayloadFieldResult> payloadFields
    ) {
    }

    record PayloadFieldResult(
            long fieldId,
            String sourceColumnName,
            String payloadKey,
            String fieldType,
            String syncEnabled,
            String fieldStatus
    ) {
    }
}
