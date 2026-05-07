package com.krystal.vectorsidecarservice.domain.sync;

import java.time.Instant;

public record VectorSyncErrorMeta(
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
}
