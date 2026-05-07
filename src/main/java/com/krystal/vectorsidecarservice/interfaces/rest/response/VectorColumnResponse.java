package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;

import java.time.Instant;

public record VectorColumnResponse(
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
    public static VectorColumnResponse from(VectorColumnMeta meta) {
        return new VectorColumnResponse(
                meta.columnId(),
                meta.tenantId(),
                meta.schemaName(),
                meta.tableName(),
                meta.pkColumn(),
                meta.vectorColumn(),
                meta.dimension(),
                meta.metricType(),
                meta.status(),
                meta.syncMode(),
                meta.createdAt(),
                meta.updatedAt()
        );
    }
}
