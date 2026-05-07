package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.registry.VectorPayloadFieldMeta;

import java.util.List;

public interface VectorPayloadFieldPort {

    VectorPayloadFieldMeta save(VectorPayloadFieldMeta payloadFieldMeta);

    List<VectorPayloadFieldMeta> findByColumnId(long columnId);
}
