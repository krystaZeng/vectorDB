package com.krystal.vectorsidecarservice.application.registry.lifecycle;

import com.krystal.vectorsidecarservice.common.exception.BizException;

import java.util.Locale;
import java.util.Set;

public enum VectorColumnLifecycle {
    BUILDING("BUILDING"),
    ACTIVE("ACTIVE"),
    FAILED("FAILED"),
    DISABLED("DISABLED");

    private static final Set<String> STATUSES = Set.of("BUILDING", "ACTIVE", "FAILED", "DISABLED");

    private final String status;

    VectorColumnLifecycle(String status) {
        this.status = status;
    }

    public String status() {
        return status;
    }

    public boolean canTransitionTo(VectorColumnLifecycle target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return switch (this) {
            case BUILDING -> target == ACTIVE || target == FAILED || target == DISABLED;
            case ACTIVE -> target == FAILED || target == DISABLED;
            case FAILED -> target == BUILDING || target == DISABLED;
            case DISABLED -> false;
        };
    }

    public static VectorColumnLifecycle normalize(String status) {
        String normalized = normalizeRequired(status);
        for (VectorColumnLifecycle lifecycle : values()) {
            if (lifecycle.status.equals(normalized)) {
                return lifecycle;
            }
        }
        throw new BizException("column lifecycle has invalid status: " + normalized);
    }

    private static String normalizeRequired(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException("column status must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new BizException("column status has invalid value: " + normalized);
        }
        return normalized;
    }
}
