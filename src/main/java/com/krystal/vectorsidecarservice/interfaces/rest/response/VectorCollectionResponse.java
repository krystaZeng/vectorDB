package com.krystal.vectorsidecarservice.interfaces.rest.response;

import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;

import java.time.Instant;

public record VectorCollectionResponse(
        long collectionId,
        long columnId,
        String engineType,
        String namespaceName,
        String collectionName,
        String aliasName,
        String collectionVersion,
        String servingState,
        String collectionStatus,
        String qdrantVectorName,
        String qdrantIdType,
        String distanceMetric,
        int vectorDim,
        Integer shardNumber,
        Integer replicationFactor,
        Integer writeConsistencyFactor,
        String onDiskPayload,
        String hnswConfigJson,
        String quantizationConfigJson,
        String collectionConfigJson,
        Instant createdAt,
        Instant updatedAt
) {
    public static VectorCollectionResponse from(VectorCollectionMeta meta) {
        return new VectorCollectionResponse(
                meta.collectionId(),
                meta.columnId(),
                meta.engineType(),
                meta.namespaceName(),
                meta.collectionName(),
                meta.aliasName(),
                meta.collectionVersion(),
                meta.servingState(),
                meta.collectionStatus(),
                meta.qdrantVectorName(),
                meta.qdrantIdType(),
                meta.distanceMetric(),
                meta.vectorDim(),
                meta.shardNumber(),
                meta.replicationFactor(),
                meta.writeConsistencyFactor(),
                meta.onDiskPayload(),
                meta.hnswConfigJson(),
                meta.quantizationConfigJson(),
                meta.collectionConfigJson(),
                meta.createdAt(),
                meta.updatedAt()
        );
    }
}
