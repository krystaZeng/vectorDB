package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RegisterVectorIndexRequest(
        @Min(1) long columnId,
        Long collectionId,
        @NotBlank String profileName,
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
