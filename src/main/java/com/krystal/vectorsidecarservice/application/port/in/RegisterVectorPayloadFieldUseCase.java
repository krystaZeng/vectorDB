package com.krystal.vectorsidecarservice.application.port.in;

import com.krystal.vectorsidecarservice.domain.registry.VectorPayloadFieldMeta;

import java.util.List;

public interface RegisterVectorPayloadFieldUseCase {

    VectorPayloadFieldMeta register(RegisterVectorPayloadFieldCommand command);

    List<VectorPayloadFieldMeta> listByColumnId(long columnId);

    record RegisterVectorPayloadFieldCommand(
            long columnId,
            String sourceColumnName,
            String payloadKey,
            String fieldType,
            String isFilterable,
            String isReturnable,
            String isIndexed,
            String syncEnabled,
            String fieldStatus,
            String indexParamsJson
    ) {
    }
}
