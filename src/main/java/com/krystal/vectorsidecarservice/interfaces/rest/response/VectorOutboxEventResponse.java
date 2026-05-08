package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.application.port.in.ListVectorOutboxEventUseCase;

import java.time.Instant;

public record VectorOutboxEventResponse(
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
    public static VectorOutboxEventResponse from(ListVectorOutboxEventUseCase.VectorOutboxEventResult result) {
        return new VectorOutboxEventResponse(
                result.eventId(),
                result.columnId(),
                result.eventType(),
                result.eventStatus(),
                result.sourcePk(),
                result.pointId(),
                result.pkValueType(),
                result.dedupeKey(),
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
}
