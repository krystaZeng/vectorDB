package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.domain.sync.VectorSyncErrorMeta;

import java.time.Instant;

public record VectorSyncErrorResponse(
        long errorId,
        long jobId,
        long columnId,
        String partitionId,
        String sourcePk,
        String opType,
        String errorStage,
        String errorCode,
        String errorMessage,
        String payloadSnapshot,
        String dedupeKey,
        Instant firstSeenAt,
        Instant lastSeenAt,
        int retryCount,
        Instant nextRetryAt,
        String errorStatus,
        Instant createdAt,
        Instant updatedAt
) {
    public static VectorSyncErrorResponse from(VectorSyncErrorMeta meta) {
        return new VectorSyncErrorResponse(
                meta.errorId(),
                meta.jobId(),
                meta.columnId(),
                meta.partitionId(),
                meta.sourcePk(),
                meta.opType(),
                meta.errorStage(),
                meta.errorCode(),
                meta.errorMessage(),
                meta.payloadSnapshot(),
                meta.dedupeKey(),
                meta.firstSeenAt(),
                meta.lastSeenAt(),
                meta.retryCount(),
                meta.nextRetryAt(),
                meta.errorStatus(),
                meta.createdAt(),
                meta.updatedAt()
        );
    }
}
