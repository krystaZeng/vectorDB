package com.krystal.vectorsidecarservice.domain.sync;

import java.time.Instant;

public record VectorSyncProgressMeta(
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
}
