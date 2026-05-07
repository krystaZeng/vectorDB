package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;

import java.util.List;

public interface VectorCollectionPort {

    VectorCollectionMeta save(VectorCollectionMeta collectionMeta);

    List<VectorCollectionMeta> findByColumnId(long columnId);

    void updateStatus(long collectionId, String servingState, String collectionStatus);
}
