package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;

import java.util.List;

public interface VectorMetadataPort {

    VectorColumnMeta save(VectorColumnMeta meta);

    List<VectorColumnMeta> findAll();
}
