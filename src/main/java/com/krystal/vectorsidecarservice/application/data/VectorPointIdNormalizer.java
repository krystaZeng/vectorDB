package com.krystal.vectorsidecarservice.application.data;

import com.krystal.vectorsidecarservice.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.UUID;

@Component
public class VectorPointIdNormalizer {

    public NormalizedPointId normalize(Object pk, String qdrantIdType) {
        String idType = qdrantIdType == null ? "UINT64" : qdrantIdType.trim().toUpperCase(Locale.ROOT);
        return switch (idType) {
            case "UINT64" -> uint64PointId(pk);
            case "UUID" -> uuidPointId(pk);
            default -> throw new BizException("unsupported qdrantIdType: " + qdrantIdType);
        };
    }

    public String pkValueType(Object pk) {
        if (pk instanceof Number) {
            return "NUMBER";
        }
        if (pk instanceof String) {
            return "STRING";
        }
        throw new BizException("pk must be a number or string");
    }

    public String sourcePkText(Object pk, String qdrantIdType) {
        return normalize(pk, qdrantIdType).text();
    }

    public String relationalPkText(Object pk) {
        if (pk instanceof Number number) {
            return exactInteger(number).toString();
        }
        if (pk instanceof String text) {
            return text.trim();
        }
        throw new BizException("pk must be a number or string");
    }

    public Object relationalPkValue(String sourcePk, String pkValueType) {
        if ("NUMBER".equals(pkValueType)) {
            try {
                return Long.parseLong(sourcePk);
            } catch (NumberFormatException ex) {
                throw new BizException("stored sourcePk is not a valid number: " + sourcePk, ex);
            }
        }
        if ("STRING".equals(pkValueType)) {
            return sourcePk;
        }
        throw new BizException("unsupported pkValueType: " + pkValueType);
    }

    private NormalizedPointId uint64PointId(Object pk) {
        BigInteger value;
        if (pk instanceof Number number) {
            value = exactInteger(number);
        } else if (pk instanceof String text) {
            try {
                value = new BigInteger(text.trim());
            } catch (NumberFormatException ex) {
                throw new BizException("pk must be a non-negative integer for UINT64 qdrant id", ex);
            }
        } else {
            throw new BizException("pk must be a non-negative integer for UINT64 qdrant id");
        }
        if (value.signum() < 0 || value.bitLength() > 63) {
            throw new BizException("pk must be a non-negative integer for UINT64 qdrant id");
        }
        long longValue = value.longValue();
        return new NormalizedPointId(longValue, String.valueOf(longValue));
    }

    private BigInteger exactInteger(Number number) {
        try {
            return new BigDecimal(number.toString()).toBigIntegerExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new BizException("pk must be a non-negative integer for UINT64 qdrant id", ex);
        }
    }

    private NormalizedPointId uuidPointId(Object pk) {
        if (!(pk instanceof String text)) {
            throw new BizException("pk must be a UUID string for UUID qdrant id");
        }
        try {
            UUID uuid = UUID.fromString(text.trim());
            return new NormalizedPointId(uuid.toString(), uuid.toString());
        } catch (IllegalArgumentException ex) {
            throw new BizException("pk must be a UUID string for UUID qdrant id", ex);
        }
    }

    public record NormalizedPointId(Object value, String text) {
    }
}
