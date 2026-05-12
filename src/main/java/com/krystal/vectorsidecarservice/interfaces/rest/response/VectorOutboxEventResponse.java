package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.application.port.in.ListVectorOutboxEventUseCase;
import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;

import java.time.Instant;

public record VectorOutboxEventResponse(
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
    public static VectorOutboxEventResponse from(ListVectorOutboxEventUseCase.VectorOutboxEventResult result) {
        return new VectorOutboxEventResponse(
                result.eventId(),
                result.tenantId(),
                result.columnId(),
                result.eventKey(),
                result.activeKey(),
                result.eventType(),
                result.sourceOp(),
                result.eventStatus(),
                result.sourcePk(),
                result.pointId(),
                result.pkValueType(),
                result.dedupeKey(),
                result.sourceVersion(),
                result.needsResync(),
                result.retryCount(),
                result.nextRetryAt(),
                result.lockedBy(),
                result.lockedAt(),
                result.finishedAt(),
                result.errorCode(),
                result.errorMessage(),
                result.createdAt(),
                result.updatedAt()
        );
    }

    public static VectorOutboxEventResponse from(VectorOutboxEventMeta meta) {
        return new VectorOutboxEventResponse(
                meta.eventId(),
                meta.tenantId(),
                meta.columnId(),
                meta.eventKey(),
                meta.activeKey(),
                meta.eventType(),
                meta.sourceOp(),
                meta.eventStatus(),
                meta.sourcePk(),
                meta.pointId(),
                meta.pkValueType(),
                meta.dedupeKey(),
                meta.sourceVersion(),
                meta.needsResync(),
                meta.retryCount(),
                meta.nextRetryAt(),
                meta.lockedBy(),
                meta.lockedAt(),
                meta.finishedAt(),
                meta.errorCode(),
                meta.errorMessage(),
                meta.createdAt(),
                meta.updatedAt()
        );
    }
}
