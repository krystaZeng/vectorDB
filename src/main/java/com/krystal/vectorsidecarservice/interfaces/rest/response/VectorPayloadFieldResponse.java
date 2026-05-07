package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.domain.registry.VectorPayloadFieldMeta;

import java.time.Instant;

public record VectorPayloadFieldResponse(
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
    public static VectorPayloadFieldResponse from(VectorPayloadFieldMeta meta) {
        return new VectorPayloadFieldResponse(
                meta.fieldId(),
                meta.columnId(),
                meta.sourceColumnName(),
                meta.payloadKey(),
                meta.fieldType(),
                meta.isFilterable(),
                meta.isReturnable(),
                meta.isIndexed(),
                meta.syncEnabled(),
                meta.fieldStatus(),
                meta.indexParamsJson(),
                meta.createdAt(),
                meta.updatedAt()
        );
    }
}
