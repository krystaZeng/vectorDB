package com.krystal.vectorsidecarservice.domain.data;

import java.time.Instant;

public record VectorOutboxEventMeta(
        long eventId,
        String tenantId,
        long columnId,
        String eventKey,
        String activeKey,
        String eventType,
        String sourceOp,
        String eventStatus,
        String sourcePk,
        String pointId,
        String pkValueType,
        String dedupeKey,
        long sourceVersion,
        String needsResync,
        int retryCount,
        Instant nextRetryAt,
        String lockedBy,
        Instant lockedAt,
        String claimToken,
        Instant finishedAt,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
