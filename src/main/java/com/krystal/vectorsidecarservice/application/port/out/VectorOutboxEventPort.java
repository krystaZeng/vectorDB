package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VectorOutboxEventPort {

    VectorOutboxEventMeta save(VectorOutboxEventMeta event);

    Optional<VectorOutboxEventMeta> findById(long eventId);

    List<VectorOutboxEventMeta> findByStatus(String status, Long columnId, int limit);

    List<VectorOutboxEventMeta> findDue(Instant now, int limit);

    Optional<VectorOutboxEventMeta> claim(long eventId, String workerId, Instant now);

    int releaseExpiredProcessing(Instant lockedBefore, Instant retryAt, Instant now);

    void markDone(long eventId, Instant now);

    void markRetry(long eventId, int retryCount, Instant nextRetryAt, String errorCode, String errorMessage, Instant now);

    void markDead(long eventId, int retryCount, String errorCode, String errorMessage, Instant now);
}
