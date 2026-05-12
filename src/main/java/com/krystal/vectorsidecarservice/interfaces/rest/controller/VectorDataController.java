package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.DeleteVectorDataUseCase;
import com.krystal.vectorsidecarservice.application.port.in.InsertVectorDataUseCase;
import com.krystal.vectorsidecarservice.application.port.in.UpdateVectorDataUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.request.DeleteVectorDataRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.request.InsertVectorDataRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.request.UpdateVectorDataRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.DeleteVectorDataResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.InsertVectorDataResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.UpdateVectorDataResponse;
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
    private final UpdateVectorDataUseCase updateVectorDataUseCase;
    private final DeleteVectorDataUseCase deleteVectorDataUseCase;

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

    @PostMapping("/update")
    public ApiResponse<UpdateVectorDataResponse> update(@Valid @RequestBody UpdateVectorDataRequest request) {
        var result = updateVectorDataUseCase.update(
                new UpdateVectorDataUseCase.UpdateVectorDataCommand(
                        request.tenantId(),
                        request.schemaName(),
                        request.tableName(),
                        request.vectorColumn(),
                        request.pk(),
                        request.vector(),
                        request.payload()
                )
        );
        return ApiResponse.ok(UpdateVectorDataResponse.from(result));
    }

    @PostMapping("/delete")
    public ApiResponse<DeleteVectorDataResponse> delete(@Valid @RequestBody DeleteVectorDataRequest request) {
        var result = deleteVectorDataUseCase.delete(
                new DeleteVectorDataUseCase.DeleteVectorDataCommand(
                        request.tenantId(),
                        request.schemaName(),
                        request.tableName(),
                        request.vectorColumn(),
                        request.pk()
                )
        );
        return ApiResponse.ok(DeleteVectorDataResponse.from(result));
    }
}
