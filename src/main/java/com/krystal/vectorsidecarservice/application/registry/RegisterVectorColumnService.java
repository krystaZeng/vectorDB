package com.krystal.vectorsidecarservice.application.registry;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorColumnLifecycle;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RegisterVectorColumnService implements RegisterVectorColumnUseCase {

    private static final String DEFAULT_TENANT = "DEFAULT";
    private static final String DEFAULT_SCHEMA = "PUBLIC";
    private static final String DEFAULT_METRIC_TYPE = "COSINE";
    private static final String DEFAULT_VECTOR_ENCODING = "FLOAT32_LE";
    private static final String DEFAULT_SYNC_MODE = "FULL_AND_INCREMENTAL";
    private static final String DEFAULT_STATUS = "ACTIVE";
    private static final int MAX_REMARK_LEN = 1024;

    private final VectorMetadataPort vectorMetadataPort;
    private final IdGeneratorPort idGenerator;

    @Override
    public VectorColumnMeta register(RegisterVectorColumnCommand command) {
        if (command.dimension() <= 0) {
            throw new BizException("dimension must be greater than 0");
        }
        String metricType = normalizeMetricType(command.metricType());
        String vectorEncoding = normalizeVectorEncoding(command.vectorEncoding());
        String syncMode = normalizeSyncMode(command.syncMode());
        String tenantId = normalizeWithDefault(command.tenantId(), DEFAULT_TENANT);
        String schemaName = normalizeWithDefault(command.schemaName(), DEFAULT_SCHEMA);
        String tableName = trimRequired(command.tableName(), "tableName").toUpperCase(Locale.ROOT);
        String pkColumn = trimRequired(command.pkColumn(), "pkColumn").toUpperCase(Locale.ROOT);
        String vectorColumn = trimRequired(command.vectorColumn(), "vectorColumn").toUpperCase(Locale.ROOT);
        String status = normalizeStatus(command.status());
        String definitionHash = normalizeDefinitionHash(command.definitionHash(), tenantId, schemaName, tableName,
                pkColumn, vectorColumn, command.dimension(), metricType, vectorEncoding, syncMode);
        Instant now = Instant.now();

        VectorColumnMeta meta = new VectorColumnMeta(
                nextId(),
                tenantId,
                schemaName,
                tableName,
                pkColumn,
                vectorColumn,
                command.dimension(),
                metricType,
                vectorEncoding,
                status,
                syncMode,
                definitionHash,
                truncate(command.remark(), MAX_REMARK_LEN),
                now,
                now
        );
        return vectorMetadataPort.save(meta);
    }

    @Override
    public List<VectorColumnMeta> list() {
        return vectorMetadataPort.findAll();
    }

    private String trimRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BizException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String normalizeMetricType(String metricType) {
        String normalized = normalizeWithDefault(metricType, DEFAULT_METRIC_TYPE);
        if (!normalized.equals("COSINE") && !normalized.equals("L2") && !normalized.equals("IP")) {
            throw new BizException("metricType must be one of COSINE, L2, IP");
        }
        return normalized;
    }

    private String normalizeSyncMode(String syncMode) {
        String normalized = normalizeWithDefault(syncMode, DEFAULT_SYNC_MODE);
        if (!normalized.equals("FULL_AND_INCREMENTAL") && !normalized.equals("FULL_ONLY")) {
            throw new BizException("syncMode must be one of FULL_AND_INCREMENTAL, FULL_ONLY");
        }
        return normalized;
    }

    private String normalizeVectorEncoding(String vectorEncoding) {
        String normalized = normalizeWithDefault(vectorEncoding, DEFAULT_VECTOR_ENCODING);
        if (!normalized.equals("FLOAT32_LE") && !normalized.equals("FLOAT16_LE") && !normalized.equals("INT8")) {
            throw new BizException("vectorEncoding must be one of FLOAT32_LE, FLOAT16_LE, INT8");
        }
        return normalized;
    }

    private String normalizeStatus(String status) {
        String normalized = normalizeWithDefault(status, DEFAULT_STATUS);
        return VectorColumnLifecycle.normalize(normalized).status();
    }

    private String normalizeDefinitionHash(
            String value,
            String tenantId,
            String schemaName,
            String tableName,
            String pkColumn,
            String vectorColumn,
            int dimension,
            String metricType,
            String vectorEncoding,
            String syncMode
    ) {
        if (value != null && !value.isBlank()) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
        String canonical = String.join("|",
                tenantId,
                schemaName,
                tableName,
                pkColumn,
                vectorColumn,
                String.valueOf(dimension),
                metricType,
                vectorEncoding,
                syncMode
        );
        return sha256(canonical);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String normalizeWithDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private long nextId() {
        return idGenerator.nextId();
    }
}
