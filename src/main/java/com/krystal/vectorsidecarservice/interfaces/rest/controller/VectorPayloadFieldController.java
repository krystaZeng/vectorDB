package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorPayloadFieldUseCase;
import com.krystal.vectorsidecarservice.interfaces.rest.request.RegisterVectorPayloadFieldRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.VectorPayloadFieldResponse;
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
@RequestMapping("/api/v1/vector-payload-fields")
@RequiredArgsConstructor
public class VectorPayloadFieldController {

    private final RegisterVectorPayloadFieldUseCase registerVectorPayloadFieldUseCase;

    @PostMapping
    public ApiResponse<VectorPayloadFieldResponse> register(@Valid @RequestBody RegisterVectorPayloadFieldRequest request) {
        var result = registerVectorPayloadFieldUseCase.register(
                new RegisterVectorPayloadFieldUseCase.RegisterVectorPayloadFieldCommand(
                        request.columnId(),
                        request.sourceColumnName(),
                        request.payloadKey(),
                        request.fieldType(),
                        request.isFilterable(),
                        request.isReturnable(),
                        request.isIndexed(),
                        request.syncEnabled(),
                        request.fieldStatus(),
                        request.indexParamsJson(),
                        request.payloadIndexStatus()
                )
        );
        return ApiResponse.ok(VectorPayloadFieldResponse.from(result));
    }

    @GetMapping
    public ApiResponse<List<VectorPayloadFieldResponse>> list(@RequestParam long columnId) {
        List<VectorPayloadFieldResponse> data = registerVectorPayloadFieldUseCase.listByColumnId(columnId)
                .stream()
                .map(VectorPayloadFieldResponse::from)
                .toList();
        return ApiResponse.ok(data);
    }
}
