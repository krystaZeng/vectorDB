package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
import com.krystal.vectorsidecarservice.application.port.in.VerifyAndActivateVectorColumnUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.request.RegisterVectorColumnRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.VerifyAndActivateVectorColumnResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.VectorColumnResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vector-columns")
@RequiredArgsConstructor
public class VectorRegistryController {

    private final RegisterVectorColumnUseCase registerVectorColumnUseCase;
    private final VerifyAndActivateVectorColumnUseCase verifyAndActivateVectorColumnUseCase;

    @PostMapping
    public ApiResponse<VectorColumnResponse> register(@Valid @RequestBody RegisterVectorColumnRequest request) {
        var result = registerVectorColumnUseCase.register(
                new RegisterVectorColumnUseCase.RegisterVectorColumnCommand(
                        request.tenantId(),
                        request.schemaName(),
                        request.tableName(),
                        request.pkColumn(),
                        request.vectorColumn(),
                        request.dimension(),
                        request.metricType(),
                        request.vectorEncoding(),
                        request.syncMode(),
                        request.status(),
                        null,
                        null,
                        true
                )
        );
        return ApiResponse.ok(VectorColumnResponse.from(result));
    }

    @PostMapping("/{columnId}/verify-and-activate")
    public ApiResponse<VerifyAndActivateVectorColumnResponse> verifyAndActivate(@PathVariable long columnId) {
        var result = verifyAndActivateVectorColumnUseCase.verifyAndActivate(
                new VerifyAndActivateVectorColumnUseCase.VerifyAndActivateVectorColumnCommand(columnId)
        );
        return ApiResponse.ok(VerifyAndActivateVectorColumnResponse.from(result));
    }

    @GetMapping
    public ApiResponse<List<VectorColumnResponse>> list() {
        List<VectorColumnResponse> response = registerVectorColumnUseCase.list()
                .stream()
                .map(VectorColumnResponse::from)
                .toList();
        return ApiResponse.ok(response);
    }
}
