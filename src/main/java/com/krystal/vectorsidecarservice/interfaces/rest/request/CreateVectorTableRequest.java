package com.krystal.vectorsidecarservice.interfaces.rest.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateVectorTableRequest(
        String tenantId,
        String schemaName,
        @NotBlank String tableName,
        String engineType,
        Boolean ifNotExists,
        Boolean autoRegisterCollection,
        Boolean autoRegisterIndex,
        String defaultIndexProfileName,
        @NotNull @Valid PrimaryKeyRequest primaryKey,
        @Valid List<@Valid ScalarColumnRequest> scalarColumns,
        @Valid VectorColumnRequest vectorColumn
) {

    public record PrimaryKeyRequest(
            @NotBlank String name,
            @NotBlank String type
    ) {
    }

    public record ScalarColumnRequest(
            @NotBlank String name,
            @NotBlank String type,
            @Min(1) Integer length,
            Boolean nullable
    ) {
    }

    public record VectorColumnRequest(
            @NotBlank String name,
            @Min(1) int dimension,
            String elementType,
            String metricType,
            String syncMode,
            Boolean nullable
    ) {
    }
}
