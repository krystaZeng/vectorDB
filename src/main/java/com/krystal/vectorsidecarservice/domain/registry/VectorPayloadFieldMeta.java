package com.krystal.vectorsidecarservice.domain.registry;

import java.time.Instant;

public record VectorPayloadFieldMeta(
        long fieldId,
        long columnId,
        String sourceColumnName,
        String payloadKey,
        String fieldType,
        String isFilterable,
        String isReturnable,
        String isIndexed,
        String syncEnabled,
        String fieldStatus,
        String indexParamsJson,
        Instant createdAt,
        Instant updatedAt
) {
}
