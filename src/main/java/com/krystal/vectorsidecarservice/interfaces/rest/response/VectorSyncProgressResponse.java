package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.domain.sync.VectorSyncProgressMeta;

import java.time.Instant;

public record VectorSyncProgressResponse(
        long progressId,
        long jobId,
        long columnId,
        String partitionId,
        String lastPk,
        Instant lastEventTime,
        String lastEventId,
        long processedRows,
        long successRows,
        long failedRows,
        Instant lastBatchTime,
        String progressStatus,
        String checkpointData,
        Instant updatedAt
) {
    public static VectorSyncProgressResponse from(VectorSyncProgressMeta meta) {
        return new VectorSyncProgressResponse(
                meta.progressId(),
                meta.jobId(),
                meta.columnId(),
                meta.partitionId(),
                meta.lastPk(),
                meta.lastEventTime(),
                meta.lastEventId(),
                meta.processedRows(),
                meta.successRows(),
                meta.failedRows(),
                meta.lastBatchTime(),
                meta.progressStatus(),
                meta.checkpointData(),
                meta.updatedAt()
        );
    }
}
