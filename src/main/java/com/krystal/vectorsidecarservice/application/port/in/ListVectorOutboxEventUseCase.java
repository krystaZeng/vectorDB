package com.krystal.vectorsidecarservice.application.port.in;

import java.time.Instant;
import java.util.List;

public interface ListVectorOutboxEventUseCase {

    List<VectorOutboxEventResult> list(ListVectorOutboxEventQuery query);

    record ListVectorOutboxEventQuery(String status, Long columnId, Integer limit) {
    }

    record VectorOutboxEventResult(
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
            Instant finishedAt,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
