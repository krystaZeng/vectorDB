package com.krystal.vectorsidecarservice.domain.data;

import java.time.Instant;

public record VectorOutboxEventMeta(
        long eventId,
        long columnId,
        String eventType,
        String eventStatus,
        String sourcePk,
        String pointId,
        String pkValueType,
        String dedupeKey,
        int retryCount,
        Instant nextRetryAt,
        String lockedBy,
        Instant lockedAt,
        Instant finishedAt,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
