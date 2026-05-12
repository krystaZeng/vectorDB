package com.krystal.vectorsidecarservice.application.data;

import com.krystal.vectorsidecarservice.application.port.in.ListVectorOutboxEventUseCase;
import com.krystal.vectorsidecarservice.application.port.out.VectorOutboxEventPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ListVectorOutboxEventService implements ListVectorOutboxEventUseCase {

    private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "DONE", "RETRYING", "DEAD");

    private final VectorOutboxEventPort vectorOutboxEventPort;

    @Override
    public List<VectorOutboxEventResult> list(ListVectorOutboxEventQuery query) {
        String status = normalizeStatus(query == null ? null : query.status());
        Long columnId = query == null ? null : query.columnId();
        if (columnId != null && columnId <= 0) {
            throw new BizException("columnId must be positive");
        }
        int limit = normalizeLimit(query == null ? null : query.limit());
        return vectorOutboxEventPort.findByStatus(status, columnId, limit)
                .stream()
                .map(this::toResult)
                .toList();
    }

    private VectorOutboxEventResult toResult(VectorOutboxEventMeta meta) {
        return new VectorOutboxEventResult(
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

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new BizException("status must be one of " + STATUSES);
        }
        return normalized;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 100;
        }
        if (limit <= 0 || limit > 500) {
            throw new BizException("limit must be between 1 and 500");
        }
        return limit;
    }
}
