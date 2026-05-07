package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RegisterVectorPayloadFieldRequest(
        @Min(1) long columnId,
        @NotBlank String sourceColumnName,
        @NotBlank String payloadKey,
        String fieldType,
        String isFilterable,
        String isReturnable,
        String isIndexed,
        String syncEnabled,
        String fieldStatus,
        String indexParamsJson
) {
}
