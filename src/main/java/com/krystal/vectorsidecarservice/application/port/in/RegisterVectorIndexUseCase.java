package com.krystal.vectorsidecarservice.application.port.in;

import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;

import java.util.List;

public interface RegisterVectorIndexUseCase {

    VectorIndexMeta register(RegisterVectorIndexCommand command);

    List<VectorIndexMeta> listByColumnId(long columnId);

    record RegisterVectorIndexCommand(
            long columnId,
            Long collectionId,
            String profileName,
            String indexType,
            String metricType,
            String indexParamsJson,
            String searchParamsJson,
            String payloadIndexJson,
            String isDefault,
            String servingState,
            String indexStatus,
            String buildVersion
    ) {
    }
}
