package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.domain.sync.VectorSyncJobMeta;

import java.time.Instant;

public record VectorSyncJobResponse(
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
    public static VectorSyncJobResponse from(VectorSyncJobMeta meta) {
        return new VectorSyncJobResponse(
                meta.jobId(),
                meta.columnId(),
                meta.collectionId(),
                meta.indexId(),
                meta.jobType(),
                meta.jobStatus(),
                meta.triggerType(),
                meta.idempotencyKey(),
                meta.snapshotId(),
                meta.sourceCursor(),
                meta.startPk(),
                meta.endPk(),
                meta.workerId(),
                meta.attemptNo(),
                meta.retryCount(),
                meta.errorCode(),
                meta.errorMessage(),
                meta.heartbeatAt(),
                meta.startedAt(),
                meta.finishedAt(),
                meta.createdAt(),
                meta.updatedAt()
        );
    }
}
