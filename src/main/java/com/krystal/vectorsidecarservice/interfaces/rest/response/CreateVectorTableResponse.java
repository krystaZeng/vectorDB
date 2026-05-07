package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.application.port.in.CreateVectorTableUseCase;

public record CreateVectorTableResponse(
        String schemaName,
        String tableName,
        String vectorColumn,
        int dimension,
        String metricType,
        long columnId,
        Long collectionId,
        Long indexId,
        String indexProfileName,
        boolean ddlExecuted,
        String ddl
) {
    public static CreateVectorTableResponse from(CreateVectorTableUseCase.CreateVectorTableResult result) {
        return new CreateVectorTableResponse(
                result.schemaName(),
                result.tableName(),
                result.vectorColumn(),
                result.dimension(),
                result.metricType(),
                result.columnId(),
                result.collectionId(),
                result.indexId(),
                result.indexProfileName(),
                result.ddlExecuted(),
                result.ddl()
        );
    }
}
