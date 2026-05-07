package com.krystal.vectorsidecarservice.domain.registry;

import java.time.Instant;

public record VectorIndexMeta(
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
}
