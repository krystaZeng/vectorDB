package com.krystal.vectorsidecarservice.application.port.in;

import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;

import java.util.List;

public interface RegisterVectorCollectionUseCase {

    VectorCollectionMeta register(RegisterVectorCollectionCommand command);

    List<VectorCollectionMeta> listByColumnId(long columnId);

    record RegisterVectorCollectionCommand(
            long columnId,
            String engineType,
            String namespaceName,
            String collectionName,
            String aliasName,
            String collectionVersion,
            String qdrantVectorName,
            Integer vectorDim,
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
}
