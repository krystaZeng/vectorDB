package com.krystal.vectorsidecarservice.application.port.in;

import java.util.List;
import java.util.Map;

public interface UpdateVectorDataUseCase {

    UpdateVectorDataResult update(UpdateVectorDataCommand command);

    record UpdateVectorDataCommand(
            String tenantId,
            String schemaName,
            String tableName,
            String vectorColumn,
            Object pk,
            List<Double> vector,
            Map<String, Object> payload
    ) {
    }

    record UpdateVectorDataResult(
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
    }
}
