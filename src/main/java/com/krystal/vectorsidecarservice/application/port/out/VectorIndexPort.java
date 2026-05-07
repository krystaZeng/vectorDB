package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;

import java.util.List;

public interface VectorIndexPort {

    VectorIndexMeta save(VectorIndexMeta indexMeta);

    List<VectorIndexMeta> findByColumnId(long columnId);

    void updateStatus(long indexId, String servingState, String indexStatus);
}
