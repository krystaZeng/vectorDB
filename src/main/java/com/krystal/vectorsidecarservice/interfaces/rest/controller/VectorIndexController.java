package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorIndexUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.request.RegisterVectorIndexRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.VectorIndexResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/vector-indexes")
@RequiredArgsConstructor
public class VectorIndexController {

    private final RegisterVectorIndexUseCase registerVectorIndexUseCase;

    @PostMapping
    public ApiResponse<VectorIndexResponse> register(@Valid @RequestBody RegisterVectorIndexRequest request) {
        var result = registerVectorIndexUseCase.register(
                new RegisterVectorIndexUseCase.RegisterVectorIndexCommand(
                        request.columnId(),
                        request.collectionId(),
                        request.profileName(),
                        request.indexType(),
                        request.metricType(),
                        request.indexParamsJson(),
                        request.searchParamsJson(),
                        request.payloadIndexJson(),
                        request.isDefault(),
                        request.servingState(),
                        request.indexStatus(),
                        request.buildVersion()
                )
        );
        return ApiResponse.ok(VectorIndexResponse.from(result));
    }

    @GetMapping
    public ApiResponse<List<VectorIndexResponse>> list(@RequestParam long columnId) {
        List<VectorIndexResponse> data = registerVectorIndexUseCase.listByColumnId(columnId)
                .stream()
                .map(VectorIndexResponse::from)
                .toList();
        return ApiResponse.ok(data);
    }
}
