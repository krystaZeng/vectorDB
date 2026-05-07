package com.krystal.vectorsidecarservice.domain.sync;

import java.time.Instant;

public record VectorSyncJobMeta(
        long jobId,
        long columnId,
        Long collectionId,
        Long indexId,
        String jobType,
        String jobStatus,
        String triggerType,
        String idempotencyKey,
        String snapshotId,
        String sourceCursor,
        String startPk,
        String endPk,
        String workerId,
        int attemptNo,
        int retryCount,
        String errorCode,
        String errorMessage,
        Instant heartbeatAt,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
