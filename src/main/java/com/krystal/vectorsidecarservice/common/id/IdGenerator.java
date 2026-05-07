package com.krystal.vectorsidecarservice.common.id;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class IdGenerator {

    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() * 1000L);

    public long nextId() {
        return sequence.incrementAndGet();
    }
}
