package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterVectorCollectionRequest(
        @Min(1) long columnId,
        String engineType,
        @NotBlank String namespaceName,
        @NotBlank String collectionName,
        String aliasName,
        String collectionVersion,
        String qdrantVectorName,
        @NotNull @Min(1) Integer vectorDim,
        String distanceMetric,
        String qdrantIdType,
        String servingState,
        String collectionStatus,
        Integer shardNumber,
        Integer replicationFactor,
        Integer writeConsistencyFactor,
        String onDiskPayload,
        String hnswConfigJson,
        String quantizationConfigJson,
        String collectionConfigJson
) {
}
