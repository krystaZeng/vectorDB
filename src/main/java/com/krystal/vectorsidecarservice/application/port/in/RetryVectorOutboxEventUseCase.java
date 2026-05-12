package com.krystal.vectorsidecarservice.application.port.in;

import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;

public interface RetryVectorOutboxEventUseCase {

    VectorOutboxEventMeta retryDead(long eventId);
}
