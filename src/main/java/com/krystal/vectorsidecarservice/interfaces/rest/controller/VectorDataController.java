package com.krystal.vectorsidecarservice.interfaces.rest.controller;

import com.krystal.vectorsidecarservice.application.port.in.DeleteVectorDataUseCase;
import com.krystal.vectorsidecarservice.application.port.in.InsertVectorDataUseCase;
import com.krystal.vectorsidecarservice.application.port.in.SelectVectorDataUseCase;
import com.krystal.vectorsidecarservice.application.port.in.UpdateVectorDataUseCase;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.interfaces.rest.request.DeleteVectorDataRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.request.InsertVectorDataRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.request.SelectVectorDataRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.request.UpdateVectorDataRequest;
import com.krystal.vectorsidecarservice.interfaces.rest.response.ApiResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.DeleteVectorDataResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.InsertVectorDataResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.SelectVectorDataResponse;
import com.krystal.vectorsidecarservice.interfaces.rest.response.UpdateVectorDataResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/vector-data")
@RequiredArgsConstructor
public class VectorDataController {

    private final InsertVectorDataUseCase insertVectorDataUseCase;
    private final UpdateVectorDataUseCase updateVectorDataUseCase;
    private final DeleteVectorDataUseCase deleteVectorDataUseCase;
    private final SelectVectorDataUseCase selectVectorDataUseCase;
    private final ObjectMapper objectMapper;

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
    public ApiResponse<UpdateVectorDataResponse> update(@RequestBody JsonNode requestBody) {
        UpdateVectorDataRequest request = updateRequest(requestBody);
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

    @PostMapping("/select")
    public ApiResponse<SelectVectorDataResponse> select(@RequestBody JsonNode requestBody) {
        SelectVectorDataRequest request = selectRequest(requestBody);
        var result = selectVectorDataUseCase.select(
                new SelectVectorDataUseCase.SelectVectorDataCommand(
                        request.tenantId(),
                        request.schemaName(),
                        request.tableName(),
                        request.vectorColumn(),
                        request.select(),
                        selectConditions(request, requestBody),
                        request.orderBy() == null ? null : request.orderBy().stream()
                                .map(order -> new SelectVectorDataUseCase.OrderBy(
                                        order.field(),
                                        order.direction()
                                ))
                                .toList(),
                        request.limit(),
                        request.offset(),
                        request.nearest() == null ? null : new SelectVectorDataUseCase.Nearest(
                                request.nearest().vector(),
                                request.nearest().topK(),
                                request.nearest().scoreThreshold()
                        )
                )
        );
        return ApiResponse.ok(SelectVectorDataResponse.from(result));
    }

    private SelectVectorDataRequest selectRequest(JsonNode requestBody) {
        if (requestBody == null || requestBody.isNull()) {
            throw new BizException("request must not be null");
        }
        try {
            SelectVectorDataRequest request = objectMapper.treeToValue(requestBody, SelectVectorDataRequest.class);
            if (request.tableName() == null || request.tableName().isBlank()) {
                throw new BizException("tableName must not be blank");
            }
            return request;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException("invalid select request", ex);
        }
    }

    private List<SelectVectorDataUseCase.SelectCondition> selectConditions(
            SelectVectorDataRequest request,
            JsonNode requestBody
    ) {
        if (request.where() == null) {
            return null;
        }
        List<JsonNode> conditionNodes = whereConditionNodes(requestBody);
        if (conditionNodes.size() != request.where().size()) {
            throw new BizException("invalid select request: where must be an array");
        }
        List<SelectVectorDataUseCase.SelectCondition> conditions = new ArrayList<>();
        for (int i = 0; i < request.where().size(); i++) {
            SelectVectorDataRequest.SelectConditionRequest condition = request.where().get(i);
            JsonNode conditionNode = conditionNodes.get(i);
            conditions.add(new SelectVectorDataUseCase.SelectCondition(
                    condition.field(),
                    condition.op(),
                    condition.value(),
                    condition.values(),
                    conditionNode.has("value"),
                    conditionNode.has("values")
            ));
        }
        return conditions;
    }

    private List<JsonNode> whereConditionNodes(JsonNode requestBody) {
        JsonNode whereNode = requestBody.get("where");
        if (whereNode == null || whereNode.isNull()) {
            return List.of();
        }
        if (!whereNode.isArray()) {
            throw new BizException("invalid select request: where must be an array");
        }
        List<JsonNode> conditionNodes = new ArrayList<>();
        for (JsonNode conditionNode : whereNode.values()) {
            conditionNodes.add(conditionNode);
        }
        return conditionNodes;
    }

    private UpdateVectorDataRequest updateRequest(JsonNode requestBody) {
        if (requestBody == null || requestBody.isNull()) {
            throw new BizException("request must not be null");
        }
        if (requestBody.has("vector") && requestBody.get("vector").isNull()) {
            throw new BizException("INVALID_VECTOR_VALUE: vector must not be null");
        }
        try {
            UpdateVectorDataRequest request = objectMapper.treeToValue(requestBody, UpdateVectorDataRequest.class);
            if (request.tableName() == null || request.tableName().isBlank()) {
                throw new BizException("tableName must not be blank");
            }
            if (request.pk() == null) {
                throw new BizException("pk must not be null");
            }
            return request;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException("invalid update request", ex);
        }
    }
}
