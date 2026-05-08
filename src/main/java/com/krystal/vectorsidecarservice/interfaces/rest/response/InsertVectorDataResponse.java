package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.application.port.in.InsertVectorDataUseCase;

public record InsertVectorDataResponse(
        String tenantId,
        String schemaName,
        String tableName,
        String vectorColumn,
        long columnId,
        Long collectionId,
        String collectionName,
        String writeTargetName,
        String pointId,
        boolean relationalInserted,
        boolean vectorInserted,
        String vectorUpsertStatus,
        String vectorUpsertMessage
) {
    public static InsertVectorDataResponse from(InsertVectorDataUseCase.InsertVectorDataResult result) {
        return new InsertVectorDataResponse(
                result.tenantId(),
                result.schemaName(),
                result.tableName(),
                result.vectorColumn(),
                result.columnId(),
                result.collectionId(),
                result.collectionName(),
                result.writeTargetName(),
                result.pointId(),
                result.relationalInserted(),
                result.vectorInserted(),
                result.vectorUpsertStatus(),
                result.vectorUpsertMessage()
        );
    }
}
