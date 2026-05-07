package com.krystal.vectorsidecarservice.application.registry.lifecycle;

import com.krystal.vectorsidecarservice.common.exception.BizException;

import java.util.Locale;
import java.util.Set;

public enum VectorIndexLifecycle {
    CREATING("OFFLINE", "CREATING"),
    READY("ONLINE", "READY"),
    OFFLINE_READY("OFFLINE", "READY"),
    CANARY_READY("CANARY", "READY"),
    REBUILDING("OFFLINE", "REBUILDING"),
    FAILED("OFFLINE", "FAILED");

    private static final Set<String> SERVING_STATES = Set.of("ONLINE", "OFFLINE", "CANARY");
    private static final Set<String> INDEX_STATUSES = Set.of("CREATING", "READY", "FAILED", "REBUILDING");

    private final String servingState;
    private final String indexStatus;

    VectorIndexLifecycle(String servingState, String indexStatus) {
        this.servingState = servingState;
        this.indexStatus = indexStatus;
    }

    public String servingState() {
        return servingState;
    }

    public String indexStatus() {
        return indexStatus;
    }

    public boolean canTransitionTo(VectorIndexLifecycle target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return switch (this) {
            case CREATING -> target == READY || target == OFFLINE_READY || target == CANARY_READY || target == FAILED;
            case READY -> target == OFFLINE_READY || target == CANARY_READY || target == REBUILDING || target == FAILED;
            case OFFLINE_READY -> target == READY || target == CANARY_READY || target == REBUILDING || target == FAILED;
            case CANARY_READY -> target == READY || target == OFFLINE_READY || target == REBUILDING || target == FAILED;
            case REBUILDING -> target == READY || target == OFFLINE_READY || target == CANARY_READY || target == FAILED;
            case FAILED -> target == CREATING || target == REBUILDING;
        };
    }

    public static VectorIndexLifecycle normalize(String servingState, String indexStatus) {
        String normalizedServingState = normalizeEnum(servingState, READY.servingState, SERVING_STATES, "servingState");
        String normalizedIndexStatus = normalizeEnum(indexStatus, READY.indexStatus, INDEX_STATUSES, "indexStatus");
        return fromNormalized(normalizedServingState, normalizedIndexStatus);
    }

    public static VectorIndexLifecycle fromPersisted(String servingState, String indexStatus) {
        String normalizedServingState = normalizeRequiredEnum(servingState, SERVING_STATES, "servingState");
        String normalizedIndexStatus = normalizeRequiredEnum(indexStatus, INDEX_STATUSES, "indexStatus");
        return fromNormalized(normalizedServingState, normalizedIndexStatus);
    }

    public String display() {
        return servingState + "/" + indexStatus;
    }

    private static VectorIndexLifecycle fromNormalized(String servingState, String indexStatus) {
        for (VectorIndexLifecycle lifecycle : values()) {
            if (lifecycle.servingState.equals(servingState) && lifecycle.indexStatus.equals(indexStatus)) {
                return lifecycle;
            }
        }
        throw new BizException("index lifecycle has invalid state combination: servingState="
                + servingState + ", indexStatus=" + indexStatus);
    }

    private static String normalizeEnum(String value, String defaultValue, Set<String> allowed, String fieldName) {
        String normalized = value == null || value.isBlank()
                ? defaultValue
                : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BizException(fieldName + " has invalid value: " + normalized);
        }
        return normalized;
    }

    private static String normalizeRequiredEnum(String value, Set<String> allowed, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BizException(fieldName + " must not be blank");
        }
        return normalizeEnum(value, null, allowed, fieldName);
    }
}
