package com.krystal.vectorsidecarservice.domain.registry;

import java.time.Instant;

public record VectorCollectionMeta(
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
}
