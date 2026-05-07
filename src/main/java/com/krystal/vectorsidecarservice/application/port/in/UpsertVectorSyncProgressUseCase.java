package com.krystal.vectorsidecarservice.application.port.in;

import com.krystal.vectorsidecarservice.domain.sync.VectorSyncProgressMeta;

import java.util.List;

public interface UpsertVectorSyncProgressUseCase {

    VectorSyncProgressMeta upsert(UpsertVectorSyncProgressCommand command);

    List<VectorSyncProgressMeta> listByJobId(long jobId);

    record UpsertVectorSyncProgressCommand(
            Long progressId,
            long jobId,
            long columnId,
            String partitionId,
            String lastPk,
            String lastEventId,
            Long processedRows,
            Long successRows,
            Long failedRows,
            String progressStatus,
            String checkpointData
    ) {
    }
}
