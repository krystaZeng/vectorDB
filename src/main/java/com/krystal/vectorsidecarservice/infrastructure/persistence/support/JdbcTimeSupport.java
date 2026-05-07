package com.krystal.vectorsidecarservice.infrastructure.persistence.support;

import java.sql.Timestamp;
import java.time.Instant;

public abstract class JdbcTimeSupport {

    protected Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    protected Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    protected Long epochMillis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    protected Instant instant(Long epochMillis, Timestamp fallbackTimestamp) {
        if (epochMillis != null) {
            return Instant.ofEpochMilli(epochMillis);
        }
        return instant(fallbackTimestamp);
    }
}
