package com.krystal.vectorsidecarservice.application.port.in;

public interface DeleteVectorDataUseCase {

    DeleteVectorDataResult delete(DeleteVectorDataCommand command);

    record DeleteVectorDataCommand(
            String tenantId,
            String schemaName,
            String tableName,
            String vectorColumn,
            Object pk
    ) {
    }

    record DeleteVectorDataResult(
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
    }
}
