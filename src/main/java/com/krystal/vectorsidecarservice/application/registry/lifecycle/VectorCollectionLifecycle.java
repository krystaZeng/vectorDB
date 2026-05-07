package com.krystal.vectorsidecarservice.application.registry.lifecycle;

import com.krystal.vectorsidecarservice.common.exception.BizException;

import java.util.Locale;
import java.util.Set;

public enum VectorCollectionLifecycle {
    CREATING("BUILDING", "CREATING"),
    READY("ACTIVE", "READY"),
    DEPRECATED("DEPRECATED", "READY"),
    FAILED("BUILDING", "FAILED"),
    DROPPED("DEPRECATED", "DROPPED");

    private static final Set<String> SERVING_STATES = Set.of("BUILDING", "ACTIVE", "DEPRECATED");
    private static final Set<String> COLLECTION_STATUSES = Set.of("CREATING", "READY", "FAILED", "DROPPED");

    private final String servingState;
    private final String collectionStatus;

    VectorCollectionLifecycle(String servingState, String collectionStatus) {
        this.servingState = servingState;
        this.collectionStatus = collectionStatus;
    }

    public String servingState() {
        return servingState;
    }

    public String collectionStatus() {
        return collectionStatus;
    }

    public boolean canTransitionTo(VectorCollectionLifecycle target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return switch (this) {
            case CREATING -> target == READY || target == FAILED || target == DROPPED;
            case READY -> target == DEPRECATED || target == DROPPED;
            case DEPRECATED -> target == READY || target == DROPPED;
            case FAILED -> target == CREATING || target == DROPPED;
            case DROPPED -> false;
        };
    }

    public static VectorCollectionLifecycle normalize(String servingState, String collectionStatus) {
        String normalizedServingState = normalizeEnum(servingState, READY.servingState, SERVING_STATES, "servingState");
        String normalizedCollectionStatus = normalizeEnum(
                collectionStatus,
                READY.collectionStatus,
                COLLECTION_STATUSES,
                "collectionStatus"
        );
        return fromNormalized(normalizedServingState, normalizedCollectionStatus);
    }

    public static VectorCollectionLifecycle fromPersisted(String servingState, String collectionStatus) {
        String normalizedServingState = normalizeRequiredEnum(servingState, SERVING_STATES, "servingState");
        String normalizedCollectionStatus = normalizeRequiredEnum(
                collectionStatus,
                COLLECTION_STATUSES,
                "collectionStatus"
        );
        return fromNormalized(normalizedServingState, normalizedCollectionStatus);
    }

    public String display() {
        return servingState + "/" + collectionStatus;
    }

    private static VectorCollectionLifecycle fromNormalized(String servingState, String collectionStatus) {
        for (VectorCollectionLifecycle lifecycle : values()) {
            if (lifecycle.servingState.equals(servingState) && lifecycle.collectionStatus.equals(collectionStatus)) {
                return lifecycle;
            }
        }
        throw new BizException("collection lifecycle has invalid state combination: servingState="
                + servingState + ", collectionStatus=" + collectionStatus);
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
