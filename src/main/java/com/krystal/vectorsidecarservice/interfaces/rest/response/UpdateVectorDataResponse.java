package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.application.port.in.UpdateVectorDataUseCase;

public record UpdateVectorDataResponse(
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
        boolean relationalUpdated,
        boolean vectorSyncEnqueued,
        String vectorSyncStatus,
        String vectorSyncMessage
) {
    public static UpdateVectorDataResponse from(UpdateVectorDataUseCase.UpdateVectorDataResult result) {
        return new UpdateVectorDataResponse(
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
                result.relationalUpdated(),
                result.vectorSyncEnqueued(),
                result.vectorSyncStatus(),
                result.vectorSyncMessage()
        );
    }
}
