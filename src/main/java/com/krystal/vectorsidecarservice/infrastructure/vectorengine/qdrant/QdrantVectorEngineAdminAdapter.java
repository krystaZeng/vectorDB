package com.krystal.vectorsidecarservice.infrastructure.vectorengine.qdrant;

import com.krystal.vectorsidecarservice.application.port.out.VectorEngineAdminPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorEngineDataPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class QdrantVectorEngineAdminAdapter implements VectorEngineAdminPort, VectorEngineDataPort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;

    @Autowired
    public QdrantVectorEngineAdminAdapter(
            ObjectMapper objectMapper,
            @Value("${vector.engine.qdrant.base-url:http://127.0.0.1:6333}") String baseUrl,
            @Value("${vector.engine.qdrant.enabled:false}") boolean enabled,
            @Value("${vector.engine.qdrant.api-key:}") String apiKey
    ) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.enabled = enabled;
        this.apiKey = apiKey;
    }

    QdrantVectorEngineAdminAdapter(
            ObjectMapper objectMapper,
            RestClient restClient,
            boolean enabled,
            String apiKey
    ) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.enabled = enabled;
        this.apiKey = apiKey;
    }

    @Override
    public String engineType() {
        return "QDRANT";
    }

    @Override
    public EnsureResult ensureCollection(EnsureCollectionCommand command) {
        if (!enabled) {
            return EnsureResult.skippedDisabled("qdrant provisioning is disabled");
        }
        if (collectionExists(command.collectionName())) {
            return EnsureResult.alreadyExists("collection already exists: " + command.collectionName());
        }

        ObjectNode payload = buildCollectionPayload(command);
        try {
            restClient.put()
                    .uri("/collections/{collection}", command.collectionName())
                    .headers(this::applyApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return EnsureResult.created("collection created: " + command.collectionName());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) {
                return EnsureResult.alreadyExists("collection already exists: " + command.collectionName());
            }
            throw new BizException(formatHttpError("ensureCollection", ex), ex);
        }
    }

    @Override
    public EnsureResult ensureAlias(EnsureAliasCommand command) {
        if (!enabled) {
            return EnsureResult.skippedDisabled("qdrant provisioning is disabled");
        }
        if (command.aliasName() == null || command.aliasName().isBlank()) {
            return EnsureResult.skippedNoop("alias is blank, skipping");
        }

        Optional<String> currentTarget = findAliasTarget(command.aliasName());
        if (currentTarget.isPresent()) {
            return ensureAliasPointsToExpectedCollection(
                    command.aliasName(),
                    command.collectionName(),
                    currentTarget.get()
            );
        }

        try {
            createAlias(command.aliasName(), command.collectionName());
            return EnsureResult.created("alias created: " + command.aliasName());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) {
                String target = findAliasTarget(command.aliasName())
                        .orElseThrow(() -> new BizException("qdrant alias exists but target cannot be resolved: "
                                + command.aliasName()));
                return ensureAliasPointsToExpectedCollection(command.aliasName(), command.collectionName(), target);
            }
            throw new BizException(formatHttpError("ensureAlias", ex), ex);
        }
    }

    @Override
    public EnsureResult ensureIndex(EnsureIndexCommand command) {
        if (!enabled) {
            return EnsureResult.skippedDisabled("qdrant provisioning is disabled");
        }
        if (command.indexParamsJson() == null || command.indexParamsJson().isBlank()) {
            return EnsureResult.skippedNoop("indexParamsJson is blank, skipping");
        }
        JsonNode hnswConfig = readJson(command.indexParamsJson(), "indexParamsJson");
        if (!hnswConfig.isObject()) {
            throw new BizException("indexParamsJson must be a JSON object");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("hnsw_config", hnswConfig);
        try {
            restClient.patch()
                    .uri("/collections/{collection}", command.collectionName())
                    .headers(this::applyApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return EnsureResult.updated("collection index config updated: " + command.collectionName());
        } catch (RestClientResponseException ex) {
            throw new BizException(formatHttpError("ensureIndex", ex), ex);
        }
    }

    @Override
    public UpsertPointResult upsertPoint(UpsertPointCommand command) {
        if (!enabled) {
            return UpsertPointResult.skippedDisabled("qdrant data write is disabled");
        }
        ObjectNode payload = buildUpsertPointPayload(command);
        try {
            restClient.put()
                    .uri("/collections/{collection}/points?wait=true", command.collectionName())
                    .headers(this::applyApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return UpsertPointResult.upserted("point upserted: " + command.collectionName());
        } catch (RestClientResponseException ex) {
            throw new BizException(formatHttpError("upsertPoint", ex), ex);
        }
    }

    @Override
    public DeletePointResult deletePoint(DeletePointCommand command) {
        if (!enabled) {
            return DeletePointResult.skippedDisabled("qdrant data write is disabled");
        }
        ObjectNode payload = buildDeletePointPayload(command);
        try {
            restClient.post()
                    .uri("/collections/{collection}/points/delete?wait=true", command.collectionName())
                    .headers(this::applyApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return DeletePointResult.deleted("point deleted: " + command.collectionName());
        } catch (RestClientResponseException ex) {
            throw new BizException(formatHttpError("deletePoint", ex), ex);
        }
    }

    private boolean collectionExists(String collectionName) {
        try {
            restClient.get()
                    .uri("/collections/{collection}", collectionName)
                    .headers(this::applyApiKey)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                return false;
            }
            throw new BizException(formatHttpError("collectionLookup", ex), ex);
        }
    }

    private Optional<String> findAliasTarget(String aliasName) {
        try {
            JsonNode response = restClient.get()
                    .uri("/aliases")
                    .headers(this::applyApiKey)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new BizException("qdrant alias lookup returned empty response");
            }
            JsonNode aliases = response.path("result").path("aliases");
            if (!aliases.isArray()) {
                throw new BizException("qdrant alias lookup returned invalid response");
            }
            for (JsonNode alias : aliases.values()) {
                if (aliasName.equals(alias.path("alias_name").asString(null))) {
                    String target = alias.path("collection_name").asString(null);
                    if (target == null || target.isBlank()) {
                        throw new BizException("qdrant alias target cannot be resolved: " + aliasName);
                    }
                    return Optional.of(target);
                }
            }
            return Optional.empty();
        } catch (RestClientResponseException ex) {
            throw new BizException(formatHttpError("aliasLookup", ex), ex);
        }
    }

    private void createAlias(String aliasName, String collectionName) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode action = objectMapper.createObjectNode();
        ObjectNode createAlias = objectMapper.createObjectNode();
        createAlias.put("collection_name", collectionName);
        createAlias.put("alias_name", aliasName);
        action.set("create_alias", createAlias);
        payload.putArray("actions").add(action);

        restClient.post()
                .uri("/collections/aliases")
                .headers(this::applyApiKey)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private EnsureResult ensureAliasPointsToExpectedCollection(
            String aliasName,
            String expectedCollection,
            String actualCollection
    ) {
        if (expectedCollection.equals(actualCollection)) {
            return EnsureResult.alreadyExists("alias already points to collection: " + aliasName);
        }
        throw new BizException("qdrant alias " + aliasName
                + " already points to " + actualCollection
                + ", expected " + expectedCollection);
    }

    private ObjectNode buildCollectionPayload(EnsureCollectionCommand command) {
        ObjectNode payload = objectMapper.createObjectNode();

        ObjectNode vectorConfig = objectMapper.createObjectNode();
        vectorConfig.put("size", command.vectorDim());
        vectorConfig.put("distance", toQdrantDistance(command.distanceMetric()));

        if (command.qdrantVectorName() == null
                || command.qdrantVectorName().isBlank()
                || command.qdrantVectorName().equalsIgnoreCase("default")) {
            payload.set("vectors", vectorConfig);
        } else {
            ObjectNode namedVectors = objectMapper.createObjectNode();
            namedVectors.set(command.qdrantVectorName(), vectorConfig);
            payload.set("vectors", namedVectors);
        }

        payload.put("on_disk_payload", "Y".equalsIgnoreCase(command.onDiskPayload()));
        mergeJsonObject(payload, command.hnswConfigJson(), "hnsw_config");
        mergeJsonObject(payload, command.quantizationConfigJson(), "quantization_config");
        mergeExtraCollectionConfig(payload, command.collectionConfigJson());
        return payload;
    }

    private ObjectNode buildUpsertPointPayload(UpsertPointCommand command) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode point = objectMapper.createObjectNode();
        putPointId(point, command.pointId());
        putPointVector(point, command.vectorName(), command.vector());
        putPayload(point, command.payload());
        payload.putArray("points").add(point);
        return payload;
    }

    private ObjectNode buildDeletePointPayload(DeletePointCommand command) {
        ObjectNode payload = objectMapper.createObjectNode();
        var points = payload.putArray("points");
        Object pointId = command.pointId();
        if (pointId instanceof Number number) {
            points.add(number.longValue());
            return payload;
        }
        if (pointId instanceof String text && !text.isBlank()) {
            points.add(text);
            return payload;
        }
        throw new BizException("qdrant point id must be a number or string");
    }

    private void putPointId(ObjectNode point, Object pointId) {
        if (pointId instanceof Number number) {
            point.put("id", number.longValue());
            return;
        }
        if (pointId instanceof String text && !text.isBlank()) {
            point.put("id", text);
            return;
        }
        throw new BizException("qdrant point id must be a number or string");
    }

    private void putPointVector(ObjectNode point, String vectorName, java.util.List<Float> vector) {
        if (vectorName == null || vectorName.isBlank() || vectorName.equalsIgnoreCase("default")) {
            var vectorArray = point.putArray("vector");
            vector.forEach(vectorArray::add);
            return;
        }
        ObjectNode namedVectors = objectMapper.createObjectNode();
        var vectorArray = namedVectors.putArray(vectorName);
        vector.forEach(vectorArray::add);
        point.set("vector", namedVectors);
    }

    private void putPayload(ObjectNode point, Map<String, Object> payloadValues) {
        if (payloadValues == null || payloadValues.isEmpty()) {
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payloadValues.forEach((key, value) -> putScalar(payload, key, value));
        point.set("payload", payload);
    }

    private void putScalar(ObjectNode node, String key, Object value) {
        if (value == null) {
            node.putNull(key);
        } else if (value instanceof String text) {
            node.put(key, text);
        } else if (value instanceof Integer number) {
            node.put(key, number);
        } else if (value instanceof Long number) {
            node.put(key, number);
        } else if (value instanceof Float number) {
            node.put(key, number);
        } else if (value instanceof Double number) {
            node.put(key, number);
        } else if (value instanceof Boolean bool) {
            node.put(key, bool);
        } else if (value instanceof Number number) {
            node.put(key, number.doubleValue());
        } else {
            throw new BizException("qdrant payload value must be scalar for key: " + key);
        }
    }

    private void mergeJsonObject(ObjectNode target, String rawJson, String fieldName) {
        if (rawJson == null || rawJson.isBlank()) {
            return;
        }
        JsonNode node = readJson(rawJson, fieldName);
        if (!node.isObject()) {
            throw new BizException(fieldName + " must be a JSON object");
        }
        target.set(fieldName, node);
    }

    private void mergeExtraCollectionConfig(ObjectNode target, String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return;
        }
        JsonNode node = readJson(rawJson, "collectionConfigJson");
        if (!node.isObject()) {
            throw new BizException("collectionConfigJson must be a JSON object");
        }
        node.properties().forEach(entry -> target.set(entry.getKey(), entry.getValue()));
    }

    private void applyApiKey(HttpHeaders headers) {
        if (apiKey != null && !apiKey.isBlank()) {
            headers.add("api-key", apiKey);
        }
    }

    private String toQdrantDistance(String metric) {
        return switch (metric.toUpperCase(Locale.ROOT)) {
            case "COSINE" -> "Cosine";
            case "EUCLID", "L2" -> "Euclid";
            case "DOT", "IP" -> "Dot";
            default -> throw new BizException("unsupported distance metric: " + metric);
        };
    }

    private JsonNode readJson(String rawJson, String fieldName) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception ex) {
            throw new BizException(fieldName + " is not valid JSON", ex);
        }
    }

    private String formatHttpError(String action, RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "qdrant " + action + " failed, status=" + ex.getStatusCode().value();
        }
        return "qdrant " + action + " failed, status=" + ex.getStatusCode().value() + ", body=" + body;
    }
}
