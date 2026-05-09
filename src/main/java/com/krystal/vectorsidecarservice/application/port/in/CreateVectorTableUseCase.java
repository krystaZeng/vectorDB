package com.krystal.vectorsidecarservice.application.port.in;

import java.util.List;

public interface CreateVectorTableUseCase {

    CreateVectorTableResult create(CreateVectorTableCommand command);

    record CreateVectorTableCommand(
            String tenantId,
            String schemaName,
            String tableName,
            String engineType,
            Boolean ifNotExists,
            Boolean autoRegisterCollection,
            Boolean autoRegisterIndex,
            String defaultIndexProfileName,
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
            Boolean nullable
    ) {
    }

    record VectorColumnSpec(
            String name,
            int dimension,
            String elementType,
            String metricType,
            String syncMode,
            Boolean nullable
    ) {
    }

    record CreateVectorTableResult(
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
            String ddl
    ) {
    }
}
