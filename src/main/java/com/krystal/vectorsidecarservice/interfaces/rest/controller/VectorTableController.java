package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.CreateSimpleVectorTableUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.request.CreateSimpleVectorTableRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.CreateSimpleVectorTableResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vector-tables")
@RequiredArgsConstructor
public class VectorTableController {

    private final CreateSimpleVectorTableUseCase createSimpleVectorTableUseCase;

    @PostMapping
    public ApiResponse<CreateSimpleVectorTableResponse> create(@Valid @RequestBody CreateSimpleVectorTableRequest request) {
        var result = createSimpleVectorTableUseCase.create(
                new CreateSimpleVectorTableUseCase.CreateSimpleVectorTableCommand(
                        request.tenantId(),
                        request.schemaName(),
                        request.tableName(),
                        new CreateSimpleVectorTableUseCase.PrimaryKeySpec(
                                request.primaryKey().name(),
                                request.primaryKey().type()
                        ),
                        request.scalarColumns() == null ? null : request.scalarColumns().stream()
                                .map(column -> new CreateSimpleVectorTableUseCase.ScalarColumnSpec(
                                        column.name(),
                                        column.type(),
                                        column.length(),
                                        column.nullable(),
                                        column.payloadKey(),
                                        column.payloadSyncEnabled(),
                                        column.payloadFieldType()
                                ))
                                .toList(),
                        new CreateSimpleVectorTableUseCase.VectorColumnSpec(
                                request.vectorColumn().name(),
                                request.vectorColumn().dimension(),
                                request.vectorColumn().nullable()
                        )
                )
        );
        return ApiResponse.ok(CreateSimpleVectorTableResponse.from(result));
    }
}
