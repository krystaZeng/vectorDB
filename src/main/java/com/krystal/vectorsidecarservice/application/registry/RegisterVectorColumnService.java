package com.krystal.vectorsidecarservice.application.registry;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
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
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RegisterVectorColumnService implements RegisterVectorColumnUseCase {

    private static final String DEFAULT_TENANT = "DEFAULT";
    private static final String DEFAULT_SCHEMA = "PUBLIC";
    private static final String DEFAULT_METRIC_TYPE = "COSINE";
    private static final String DEFAULT_VECTOR_ENCODING = "FLOAT32_LE";
    private static final String DEFAULT_SYNC_MODE = "FULL_AND_INCREMENTAL";
    private static final String DEFAULT_STATUS = "BUILDING";
    private static final int MAX_REMARK_LEN = 1024;
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private final VectorMetadataPort vectorMetadataPort;
    private final RelationalSchemaPort relationalSchemaPort;
    private final IdGeneratorPort idGenerator;

    @Override
    public VectorColumnMeta register(RegisterVectorColumnCommand command) {
        if (command == null) {
            throw new BizException("request must not be null");
        }
        if (command.dimension() <= 0) {
            throw new BizException("dimension must be greater than 0");
        }
        String metricType = normalizeMetricType(command.metricType());
        String vectorEncoding = normalizeVectorEncoding(command.vectorEncoding());
        String syncMode = normalizeSyncMode(command.syncMode());
        String tenantId = normalizeWithDefault(command.tenantId(), DEFAULT_TENANT);
        String schemaName = normalizeIdentifier(command.schemaName(), "schemaName", DEFAULT_SCHEMA);
        String tableName = normalizeIdentifier(command.tableName(), "tableName", null);
        String pkColumn = normalizeIdentifier(command.pkColumn(), "pkColumn", null);
        String vectorColumn = normalizeIdentifier(command.vectorColumn(), "vectorColumn", null);
        boolean manualRegistration = Boolean.TRUE.equals(command.validateRelationalShape());
        if (manualRegistration) {
            validateRelationalShape(schemaName, tableName, pkColumn, vectorColumn);
        }
        String status = normalizeRegisterStatus(command.status(), manualRegistration);
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

    private String normalizeIdentifier(String value, String fieldName, String defaultValue) {
        String normalized = normalizeWithDefault(value, defaultValue);
        if (normalized == null) {
            throw new BizException(fieldName + " must not be blank");
        }
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new BizException(fieldName + " must match [A-Za-z][A-Za-z0-9_]*");
        }
        return normalized;
    }

    private void validateRelationalShape(String schemaName, String tableName, String pkColumn, String vectorColumn) {
        if (!relationalSchemaPort.tableExists(schemaName, tableName)) {
            throw new BizException("table does not exist: " + schemaName + "." + tableName);
        }
        if (!relationalSchemaPort.columnExists(schemaName, tableName, pkColumn)) {
            throw new BizException("pk column does not exist: " + schemaName + "." + tableName + "." + pkColumn);
        }
        if (!relationalSchemaPort.columnExists(schemaName, tableName, vectorColumn)) {
            throw new BizException("vector column does not exist: " + schemaName + "." + tableName + "." + vectorColumn);
        }
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

    private String normalizeRegisterStatus(String status, boolean manualRegistration) {
        String normalized = normalizeWithDefault(status, manualRegistration ? DEFAULT_STATUS : "ACTIVE");
        VectorColumnLifecycle lifecycle = VectorColumnLifecycle.normalize(normalized);
        if (manualRegistration && lifecycle == VectorColumnLifecycle.ACTIVE) {
            throw new BizException("manual vector column registration cannot create ACTIVE column; use verify-and-activate");
        }
        if (manualRegistration && lifecycle == VectorColumnLifecycle.FAILED) {
            throw new BizException("manual vector column registration cannot create FAILED column");
        }
        return lifecycle.status();
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
