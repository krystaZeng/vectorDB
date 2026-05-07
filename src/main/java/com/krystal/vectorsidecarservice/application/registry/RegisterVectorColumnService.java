package com.krystal.vectorsidecarservice.application.registry;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.common.id.IdGenerator;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RegisterVectorColumnService implements RegisterVectorColumnUseCase {

    private static final String DEFAULT_TENANT = "DEFAULT";
    private static final String DEFAULT_SCHEMA = "PUBLIC";
    private static final String DEFAULT_METRIC_TYPE = "COSINE";
    private static final String DEFAULT_SYNC_MODE = "FULL_AND_INCREMENTAL";
    private static final String DEFAULT_STATUS = "ACTIVE";

    private final VectorMetadataPort vectorMetadataPort;
    private final IdGenerator idGenerator;

    @Override
    public VectorColumnMeta register(RegisterVectorColumnCommand command) {
        if (command.dimension() <= 0) {
            throw new BizException("dimension must be greater than 0");
        }
        String metricType = normalizeMetricType(command.metricType());
        String syncMode = normalizeSyncMode(command.syncMode());
        Instant now = Instant.now();

        VectorColumnMeta meta = new VectorColumnMeta(
                nextId(),
                normalizeWithDefault(command.tenantId(), DEFAULT_TENANT),
                normalizeWithDefault(command.schemaName(), DEFAULT_SCHEMA),
                trimRequired(command.tableName(), "tableName"),
                trimRequired(command.pkColumn(), "pkColumn"),
                trimRequired(command.vectorColumn(), "vectorColumn"),
                command.dimension(),
                metricType,
                DEFAULT_STATUS,
                syncMode,
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
