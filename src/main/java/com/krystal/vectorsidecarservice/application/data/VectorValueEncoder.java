package com.krystal.vectorsidecarservice.application.data;

import com.krystal.vectorsidecarservice.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;

@Component
public class VectorValueEncoder {

    public byte[] encode(List<Double> vector, int dimension, String vectorEncoding) {
        validateVector(vector, dimension);
        return switch (normalizeEncoding(vectorEncoding)) {
            case "FLOAT32_LE" -> encodeFloat32(vector);
            case "FLOAT16_LE" -> encodeFloat16(vector);
            case "INT8" -> encodeInt8(vector);
            default -> throw new BizException("vectorEncoding must be one of FLOAT32_LE, FLOAT16_LE, INT8");
        };
    }

    public List<Float> toFloatVector(List<Double> vector, int dimension) {
        validateVector(vector, dimension);
        return vector.stream()
                .map(Double::floatValue)
                .toList();
    }

    private void validateVector(List<Double> vector, int dimension) {
        if (vector == null || vector.isEmpty()) {
            throw new BizException("vector must not be empty");
        }
        if (vector.size() != dimension) {
            throw new BizException("vector dimension mismatch: expected " + dimension + ", actual " + vector.size());
        }
        for (int i = 0; i < vector.size(); i++) {
            Double value = vector.get(i);
            if (value == null || !Double.isFinite(value)) {
                throw new BizException("vector[" + i + "] must be a finite number");
            }
        }
    }

    private byte[] encodeFloat32(List<Double> vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.size() * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (Double value : vector) {
            buffer.putFloat(value.floatValue());
        }
        return buffer.array();
    }

    private byte[] encodeFloat16(List<Double> vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.size() * Short.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (Double value : vector) {
            buffer.putShort(floatToHalf(value.floatValue()));
        }
        return buffer.array();
    }

    private byte[] encodeInt8(List<Double> vector) {
        byte[] encoded = new byte[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            double value = vector.get(i);
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE || Math.rint(value) != value) {
                throw new BizException("vector[" + i + "] must be an integer between -128 and 127 for INT8");
            }
            encoded[i] = (byte) value;
        }
        return encoded;
    }

    private short floatToHalf(float value) {
        int bits = Float.floatToIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int exponent = ((bits >>> 23) & 0xff) - 127 + 15;
        int mantissa = bits & 0x7fffff;

        if (exponent <= 0) {
            if (exponent < -10) {
                return (short) sign;
            }
            mantissa = (mantissa | 0x800000) >> (1 - exponent);
            return (short) (sign | ((mantissa + 0x1000) >> 13));
        }
        if (exponent >= 31) {
            return (short) (sign | 0x7c00);
        }
        return (short) (sign | (exponent << 10) | ((mantissa + 0x1000) >> 13));
    }

    private String normalizeEncoding(String vectorEncoding) {
        if (vectorEncoding == null || vectorEncoding.isBlank()) {
            return "FLOAT32_LE";
        }
        return vectorEncoding.trim().toUpperCase(Locale.ROOT);
    }
}
