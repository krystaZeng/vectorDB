package com.krystal.vectorsidecarservice.application.port.in;

import java.util.List;
import java.util.Map;

public interface InsertVectorDataUseCase {

    InsertVectorDataResult insert(InsertVectorDataCommand command);

    record InsertVectorDataCommand(
            String tenantId,
            String schemaName,
            String tableName,
            String vectorColumn,
            Object pk,
            List<Double> vector,
            Map<String, Object> payload
    ) {
    }

    record InsertVectorDataResult(
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
    }
}
