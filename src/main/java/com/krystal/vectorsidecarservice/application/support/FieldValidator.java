package com.krystal.vectorsidecarservice.application.support;

import com.krystal.vectorsidecarservice.common.exception.BizException;

import java.util.Locale;
import java.util.Set;

public final class FieldValidator {

    private FieldValidator() {
    }

    public static void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw new BizException(fieldName + " must be greater than 0");
        }
    }

    public static Long optionalPositive(Long value, String fieldName) {
        if (value != null && value <= 0) {
            throw new BizException(fieldName + " must be greater than 0");
        }
        return value;
    }

    public static Integer optionalPositive(Integer value, String fieldName) {
        if (value != null && value <= 0) {
            throw new BizException(fieldName + " must be greater than 0");
        }
        return value;
    }

    public static long nonNegativeOrDefault(Long value, long defaultValue, String fieldName) {
        long normalized = value == null ? defaultValue : value;
        if (normalized < 0) {
            throw new BizException(fieldName + " must be >= 0");
        }
        return normalized;
    }

    public static int nonNegativeOrDefault(Integer value, int defaultValue, String fieldName) {
        int normalized = value == null ? defaultValue : value;
        if (normalized < 0) {
            throw new BizException(fieldName + " must be >= 0");
        }
        return normalized;
    }

    public static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BizException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public static String optionalText(String value) {
        return value == null ? null : value.trim();
    }

    public static String optionalTextWithMaxLength(String value, String fieldName, int maxLength) {
        String normalized = optionalText(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new BizException(fieldName + " length must be <= " + maxLength);
        }
        return normalized;
    }

    public static String optionalTextTruncate(String value, int maxLength) {
        String normalized = optionalText(value);
        if (normalized == null || normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    public static String optionalText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    public static String normalizeEnum(String value, Set<String> allowed, String defaultValue, String fieldName) {
        String normalized = optionalText(value, defaultValue).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BizException(fieldName + " has invalid value: " + normalized);
        }
        return normalized;
    }

    public static String normalizeFlag(String value, String defaultValue, String fieldName) {
        String normalized = optionalText(value, defaultValue).toUpperCase(Locale.ROOT);
        if (!normalized.equals("Y") && !normalized.equals("N")) {
            throw new BizException(fieldName + " must be Y or N");
        }
        return normalized;
    }
}
