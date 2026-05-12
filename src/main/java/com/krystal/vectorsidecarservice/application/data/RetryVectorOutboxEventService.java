package com.krystal.vectorsidecarservice.application.data;

import com.krystal.vectorsidecarservice.application.port.in.RetryVectorOutboxEventUseCase;
import com.krystal.vectorsidecarservice.application.port.out.VectorOutboxEventPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RetryVectorOutboxEventService implements RetryVectorOutboxEventUseCase {

    private final VectorOutboxEventPort vectorOutboxEventPort;

    @Override
    public VectorOutboxEventMeta retryDead(long eventId) {
        if (eventId <= 0) {
            throw new BizException("eventId must be positive");
        }
        return vectorOutboxEventPort.retryDead(eventId, Instant.now())
                .orElseGet(() -> rejectMissingOrNonDead(eventId));
    }

    private VectorOutboxEventMeta rejectMissingOrNonDead(long eventId) {
        VectorOutboxEventMeta existing = vectorOutboxEventPort.findById(eventId)
                .orElseThrow(() -> new BizException("vector outbox event not found: " + eventId));
        throw new BizException("vector outbox event is not DEAD: " + existing.eventStatus());
    }
}
