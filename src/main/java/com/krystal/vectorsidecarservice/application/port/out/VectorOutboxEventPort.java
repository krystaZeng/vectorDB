package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VectorOutboxEventPort {

    VectorOutboxEventMeta save(VectorOutboxEventMeta event);

    SaveResult saveOrFindByDedupeKey(VectorOutboxEventMeta event);

    SaveResult enqueueOrMergeActive(VectorOutboxEventMeta event);

    Optional<VectorOutboxEventMeta> findById(long eventId);

    Optional<VectorOutboxEventMeta> findByDedupeKey(String dedupeKey);

    Optional<VectorOutboxEventMeta> findByActiveKey(String activeKey);

    List<VectorOutboxEventMeta> findByStatus(String status, Long columnId, int limit);

    List<VectorOutboxEventMeta> findDue(Instant now, int limit);

    Optional<VectorOutboxEventMeta> claim(long eventId, String workerId, String claimToken, Instant now);

    int releaseExpiredProcessing(Instant lockedBefore, Instant retryAt, Instant now);

    OwnershipUpdateStatus markDone(long eventId, String claimToken, long processedSourceVersion, Instant now);

    OwnershipUpdateStatus markRetry(
            long eventId,
            String claimToken,
            long processedSourceVersion,
            int retryCount,
            Instant nextRetryAt,
            String errorCode,
            String errorMessage,
            Instant now
    );

    OwnershipUpdateStatus markDead(
            long eventId,
            String claimToken,
            long processedSourceVersion,
            int retryCount,
            String errorCode,
            String errorMessage,
            Instant now
    );

    Optional<VectorOutboxEventMeta> retryDead(long eventId, Instant now);

    enum OwnershipUpdateStatus {
        UPDATED,
        RESYNC_REQUIRED,
        STALE_CLAIM
    }

    record SaveResult(VectorOutboxEventMeta event, boolean created) {
    }
}
