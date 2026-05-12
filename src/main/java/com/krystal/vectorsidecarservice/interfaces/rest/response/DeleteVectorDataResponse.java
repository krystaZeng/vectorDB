package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.application.port.in.DeleteVectorDataUseCase;

public record DeleteVectorDataResponse(
        String tenantId,
        String schemaName,
        String tableName,
        String vectorColumn,
        long columnId,
        Long collectionId,
        String collectionName,
        String writeTargetName,
        String pointId,
        Long outboxEventId,
        long sourceVersion,
        boolean relationalDeleted,
        boolean vectorSyncEnqueued,
        String vectorSyncStatus,
        String vectorSyncMessage
) {
    public static DeleteVectorDataResponse from(DeleteVectorDataUseCase.DeleteVectorDataResult result) {
        return new DeleteVectorDataResponse(
                result.tenantId(),
                result.schemaName(),
                result.tableName(),
                result.vectorColumn(),
                result.columnId(),
                result.collectionId(),
                result.collectionName(),
                result.writeTargetName(),
                result.pointId(),
                result.outboxEventId(),
                result.sourceVersion(),
                result.relationalDeleted(),
                result.vectorSyncEnqueued(),
                result.vectorSyncStatus(),
                result.vectorSyncMessage()
        );
    }
}
