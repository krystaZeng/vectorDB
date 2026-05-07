package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.CreateVectorTableUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.request.CreateVectorTableRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.CreateVectorTableResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vector-schemas/tables")
@RequiredArgsConstructor
public class VectorSchemaController {

    private final CreateVectorTableUseCase createVectorTableUseCase;

    @PostMapping
    public ApiResponse<CreateVectorTableResponse> createTable(@Valid @RequestBody CreateVectorTableRequest request) {
        var result = createVectorTableUseCase.create(
                new CreateVectorTableUseCase.CreateVectorTableCommand(
                        request.tenantId(),
                        request.schemaName(),
                        request.tableName(),
                        request.engineType(),
                        request.ifNotExists(),
                        request.autoRegisterCollection(),
                        request.autoRegisterIndex(),
                        request.defaultIndexProfileName(),
                        new CreateVectorTableUseCase.PrimaryKeySpec(
                                request.primaryKey().name(),
                                request.primaryKey().type()
                        ),
                        mapScalarColumns(request),
                        new CreateVectorTableUseCase.VectorColumnSpec(
                                request.vectorColumn().name(),
                                request.vectorColumn().dimension(),
                                request.vectorColumn().elementType(),
                                request.vectorColumn().metricType(),
                                request.vectorColumn().syncMode(),
                                request.vectorColumn().nullable()
                        )
                )
        );
        return ApiResponse.ok(CreateVectorTableResponse.from(result));
    }

    private List<CreateVectorTableUseCase.ScalarColumnSpec> mapScalarColumns(CreateVectorTableRequest request) {
        if (request.scalarColumns() == null || request.scalarColumns().isEmpty()) {
            return List.of();
        }
        return request.scalarColumns().stream()
                .map(scalar -> new CreateVectorTableUseCase.ScalarColumnSpec(
                        scalar.name(),
                        scalar.type(),
                        scalar.length(),
                        scalar.nullable()
                ))
                .toList();
    }
}
