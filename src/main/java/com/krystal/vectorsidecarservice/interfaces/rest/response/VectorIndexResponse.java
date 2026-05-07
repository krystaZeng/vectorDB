package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;

import java.time.Instant;

public record VectorIndexResponse(
        long indexId,
        long columnId,
        Long collectionId,
        String profileName,
        String indexType,
        String metricType,
        String indexParamsJson,
        String searchParamsJson,
        String payloadIndexJson,
        String isDefault,
        String servingState,
        String indexStatus,
        String buildVersion,
        Instant createdAt,
        Instant updatedAt
) {
    public static VectorIndexResponse from(VectorIndexMeta meta) {
        return new VectorIndexResponse(
                meta.indexId(),
                meta.columnId(),
                meta.collectionId(),
                meta.profileName(),
                meta.indexType(),
                meta.metricType(),
                meta.indexParamsJson(),
                meta.searchParamsJson(),
                meta.payloadIndexJson(),
                meta.isDefault(),
                meta.servingState(),
                meta.indexStatus(),
                meta.buildVersion(),
                meta.createdAt(),
                meta.updatedAt()
        );
    }
}
