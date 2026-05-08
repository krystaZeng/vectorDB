package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.InsertVectorDataUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.request.InsertVectorDataRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.InsertVectorDataResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vector-data")
@RequiredArgsConstructor
public class VectorDataController {

    private final InsertVectorDataUseCase insertVectorDataUseCase;

    @PostMapping("/insert")
    public ApiResponse<InsertVectorDataResponse> insert(@Valid @RequestBody InsertVectorDataRequest request) {
        var result = insertVectorDataUseCase.insert(
                new InsertVectorDataUseCase.InsertVectorDataCommand(
                        request.tenantId(),
                        request.schemaName(),
                        request.tableName(),
                        request.vectorColumn(),
                        request.pk(),
                        request.vector(),
                        request.payload()
                )
        );
        return ApiResponse.ok(InsertVectorDataResponse.from(result));
    }
}
