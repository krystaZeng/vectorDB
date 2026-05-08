package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.application.port.in.CreateSimpleVectorTableUseCase;

import java.util.List;

public record CreateSimpleVectorTableResponse(
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
        List<PayloadFieldResponse> payloadFields
) {
    public static CreateSimpleVectorTableResponse from(CreateSimpleVectorTableUseCase.CreateSimpleVectorTableResult result) {
        return new CreateSimpleVectorTableResponse(
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
                result.payloadFields().stream()
                        .map(PayloadFieldResponse::from)
                        .toList()
        );
    }

    public record PayloadFieldResponse(
            long fieldId,
            String sourceColumnName,
            String payloadKey,
            String fieldType,
            String syncEnabled,
            String fieldStatus
    ) {
        static PayloadFieldResponse from(CreateSimpleVectorTableUseCase.PayloadFieldResult result) {
            return new PayloadFieldResponse(
                    result.fieldId(),
                    result.sourceColumnName(),
                    result.payloadKey(),
                    result.fieldType(),
                    result.syncEnabled(),
                    result.fieldStatus()
            );
        }
    }
}
