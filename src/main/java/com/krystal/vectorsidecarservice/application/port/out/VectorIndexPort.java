package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;

import java.util.List;
import java.util.Optional;

public interface VectorIndexPort {

    VectorIndexMeta save(VectorIndexMeta indexMeta);

    Optional<VectorIndexMeta> findById(long indexId);

    List<VectorIndexMeta> findByColumnId(long columnId);

    void updateStatus(long indexId, String servingState, String indexStatus);
}
