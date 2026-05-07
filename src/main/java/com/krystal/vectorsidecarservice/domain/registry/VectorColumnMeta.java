package com.krystal.vectorsidecarservice.domain.registry;

import java.time.Instant;

public record VectorColumnMeta(
        long columnId,
        String tenantId,
        String schemaName,
        String tableName,
        String pkColumn,
        String vectorColumn,
        int dimension,
        String metricType,
        String status,
        String syncMode,
        Instant createdAt,
        Instant updatedAt
) {
}
