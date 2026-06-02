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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        Optional<QdrantCollectionConfig> existing = findCollectionConfig(command.collectionName());
        if (existing.isPresent()) {
            validateCollectionConfig(
                    command.collectionName(),
                    command.vectorDim(),
                    command.distanceMetric(),
                    command.qdrantVectorName(),
                    existing.get()
            );
            return EnsureResult.alreadyExists(
                    "collection already exists and config matches: " + command.collectionName()
            );
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
                QdrantCollectionConfig config = findCollectionConfig(command.collectionName())
                        .orElseThrow(() -> new BizException("qdrant collection exists but config cannot be resolved: "
                                + command.collectionName()));
                validateCollectionConfig(
                        command.collectionName(),
                        command.vectorDim(),
                        command.distanceMetric(),
                        command.qdrantVectorName(),
                        config
                );
                return EnsureResult.alreadyExists(
                        "collection already exists and config matches: " + command.collectionName()
                );
            }
            throw new BizException(formatHttpError("ensureCollection", ex), ex);
        }
    }

    @Override
    public EnsureResult verifyCollection(VerifyCollectionCommand command) {
        if (!enabled) {
            return EnsureResult.skippedDisabled("qdrant readiness validation is disabled");
        }
        QdrantCollectionConfig config = findCollectionConfig(command.collectionName())
                .orElseThrow(() -> new BizException("qdrant collection not found: " + command.collectionName()));
        validateCollectionConfig(
                command.collectionName(),
                command.vectorDim(),
                command.distanceMetric(),
                command.qdrantVectorName(),
                config
        );
        return EnsureResult.alreadyExists("collection config matches: " + command.collectionName());
    }

    @Override
    public EnsureResult verifyAlias(VerifyAliasCommand command) {
        if (!enabled) {
            return EnsureResult.skippedDisabled("qdrant alias readiness validation is disabled");
        }
        if (command.aliasName() == null || command.aliasName().isBlank()) {
            return EnsureResult.skippedNoop("alias is blank, skipping");
        }
        String target = findAliasTarget(command.aliasName())
                .orElseThrow(() -> new BizException("qdrant alias not found: " + command.aliasName()));
        return ensureAliasPointsToExpectedCollection(command.aliasName(), command.collectionName(), target);
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

    @Override
    public SearchPointsResult searchPoints(SearchPointsCommand command) {
        if (!enabled) {
            throw new BizException("qdrant search is disabled");
        }
        ObjectNode payload = buildSearchPointPayload(command);
        try {
            JsonNode response = restClient.post()
                    .uri("/collections/{collection}/points/search", command.collectionName())
                    .headers(this::applyApiKey)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            return parseSearchResult(response);
        } catch (RestClientResponseException ex) {
            throw new BizException(formatHttpError("searchPoints", ex), ex);
        }
    }

    private Optional<QdrantCollectionConfig> findCollectionConfig(String collectionName) {
        try {
            JsonNode response = restClient.get()
                    .uri("/collections/{collection}", collectionName)
                    .headers(this::applyApiKey)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new BizException("qdrant collection lookup returned empty response: " + collectionName);
            }
            return Optional.of(parseCollectionConfig(collectionName, response));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                return Optional.empty();
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

    private QdrantCollectionConfig parseCollectionConfig(String collectionName, JsonNode response) {
        JsonNode vectors = response.path("result").path("config").path("params").path("vectors");
        if (!vectors.isObject()) {
            throw new BizException("qdrant collection vectors config is invalid: " + collectionName);
        }

        if (vectors.has("size") || vectors.has("distance")) {
            return new QdrantCollectionConfig(parseVectorConfig(collectionName, "unnamed", vectors), Map.of());
        }

        Map<String, VectorConfig> namedVectors = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : vectors.properties()) {
            if (!entry.getValue().isObject()) {
                throw new BizException("qdrant named vector config is invalid: "
                        + collectionName + "." + entry.getKey());
            }
            namedVectors.put(entry.getKey(), parseVectorConfig(collectionName, entry.getKey(), entry.getValue()));
        }
        if (namedVectors.isEmpty()) {
            throw new BizException("qdrant collection vectors config is empty: " + collectionName);
        }
        return new QdrantCollectionConfig(null, Map.copyOf(namedVectors));
    }

    private VectorConfig parseVectorConfig(String collectionName, String vectorName, JsonNode vectorConfig) {
        JsonNode size = vectorConfig.path("size");
        if (!size.canConvertToInt()) {
            throw new BizException("qdrant vector size is invalid: " + collectionName + "." + vectorName);
        }
        String distance = vectorConfig.path("distance").asString(null);
        if (distance == null || distance.isBlank()) {
            throw new BizException("qdrant vector distance is invalid: " + collectionName + "." + vectorName);
        }
        return new VectorConfig(size.asInt(), distance.trim());
    }

    private void validateCollectionConfig(
            String collectionName,
            int expectedDim,
            String expectedDistanceMetric,
            String expectedVectorName,
            QdrantCollectionConfig actual
    ) {
        VectorConfig actualVector = resolveExpectedVector(collectionName, expectedVectorName, actual);
        if (actualVector.size() != expectedDim) {
            throw new BizException("qdrant collection vector dimension mismatch: collection="
                    + collectionName
                    + ", expected=" + expectedDim
                    + ", actual=" + actualVector.size());
        }

        String expectedDistance = normalizeDistanceMetric(expectedDistanceMetric);
        String actualDistance = normalizeDistanceMetric(actualVector.distance());
        if (!expectedDistance.equals(actualDistance)) {
            throw new BizException("qdrant collection distance mismatch: collection="
                    + collectionName
                    + ", expected=" + expectedDistance
                    + ", actual=" + actualDistance);
        }
    }

    private VectorConfig resolveExpectedVector(
            String collectionName,
            String expectedVectorName,
            QdrantCollectionConfig actual
    ) {
        if (expectsUnnamedVector(expectedVectorName)) {
            if (actual.unnamedVector() != null) {
                return actual.unnamedVector();
            }
            throw new BizException("qdrant collection vector mode mismatch: collection="
                    + collectionName
                    + ", expected unnamed vector, actual named vectors="
                    + actual.namedVectors().keySet());
        }

        String vectorName = expectedVectorName.trim();
        VectorConfig namedVector = actual.namedVectors().get(vectorName);
        if (namedVector != null) {
            return namedVector;
        }
        if (actual.unnamedVector() != null) {
            throw new BizException("qdrant collection vector mode mismatch: collection="
                    + collectionName
                    + ", expected named vector=" + vectorName
                    + ", actual unnamed vector");
        }
        throw new BizException("qdrant named vector not found: collection="
                + collectionName
                + ", vectorName=" + vectorName
                + ", actual named vectors=" + actual.namedVectors().keySet());
    }

    private boolean expectsUnnamedVector(String qdrantVectorName) {
        // In this system, null/blank/default means Qdrant unnamed vector.
        // A Qdrant named vector literally called "default" is not supported by this path.
        return qdrantVectorName == null
                || qdrantVectorName.isBlank()
                || "default".equalsIgnoreCase(qdrantVectorName.trim());
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

    private ObjectNode buildSearchPointPayload(SearchPointsCommand command) {
        ObjectNode payload = objectMapper.createObjectNode();
        putSearchVector(payload, command.vectorName(), command.vector());
        payload.put("limit", command.limit());
        payload.put("with_payload", command.withPayload());
        if (command.scoreThreshold() != null) {
            payload.put("score_threshold", command.scoreThreshold());
        }
        ObjectNode filter = buildSearchFilter(command.filters());
        if (filter != null) {
            payload.set("filter", filter);
        }
        return payload;
    }

    private void putSearchVector(ObjectNode payload, String vectorName, List<Float> vector) {
        if (vectorName == null || vectorName.isBlank() || vectorName.equalsIgnoreCase("default")) {
            var vectorArray = payload.putArray("vector");
            vector.forEach(vectorArray::add);
            return;
        }
        ObjectNode namedVector = objectMapper.createObjectNode();
        namedVector.put("name", vectorName);
        var vectorArray = namedVector.putArray("vector");
        vector.forEach(vectorArray::add);
        payload.set("vector", namedVector);
    }

    private ObjectNode buildSearchFilter(List<SearchFilterCondition> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        ObjectNode filter = objectMapper.createObjectNode();
        var must = filter.putArray("must");
        var mustNot = filter.putArray("must_not");
        for (SearchFilterCondition condition : filters) {
            String op = condition.op() == null ? "" : condition.op().trim().toUpperCase(Locale.ROOT);
            if ("IS_NOT_NULL".equals(op)) {
                ObjectNode clause = objectMapper.createObjectNode();
                ObjectNode isNull = objectMapper.createObjectNode();
                isNull.put("key", condition.payloadKey());
                clause.set("is_null", isNull);
                mustNot.add(clause);
                continue;
            }
            must.add(searchFilterClause(condition, op));
        }
        if (must.isEmpty()) {
            filter.remove("must");
        }
        if (mustNot.isEmpty()) {
            filter.remove("must_not");
        }
        return filter;
    }

    private ObjectNode searchFilterClause(SearchFilterCondition condition, String op) {
        ObjectNode clause = objectMapper.createObjectNode();
        if ("IS_NULL".equals(op)) {
            ObjectNode isNull = objectMapper.createObjectNode();
            isNull.put("key", condition.payloadKey());
            clause.set("is_null", isNull);
            return clause;
        }
        clause.put("key", condition.payloadKey());
        switch (op) {
            case "EQ" -> {
                ObjectNode match = objectMapper.createObjectNode();
                putFilterScalar(match, "value", condition.value());
                clause.set("match", match);
            }
            case "IN" -> {
                ObjectNode match = objectMapper.createObjectNode();
                var any = match.putArray("any");
                List<Object> values = condition.values() == null ? List.of() : condition.values();
                for (Object value : values) {
                    addFilterScalar(any, value);
                }
                clause.set("match", match);
            }
            case "GT", "GTE", "LT", "LTE" -> {
                ObjectNode range = objectMapper.createObjectNode();
                putFilterScalar(range, qdrantRangeOperator(op), condition.value());
                clause.set("range", range);
            }
            default -> throw new BizException("unsupported qdrant filter op: " + condition.op());
        }
        return clause;
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

    private void putFilterScalar(ObjectNode node, String key, Object value) {
        requireFilterScalar(value);
        putScalar(node, key, value);
    }

    private void addFilterScalar(tools.jackson.databind.node.ArrayNode array, Object value) {
        requireFilterScalar(value);
        addScalar(array, value);
    }

    private void requireFilterScalar(Object value) {
        if (value == null) {
            throw new BizException("qdrant filter value must not be null");
        }
        if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
            throw new BizException("qdrant filter value must be a finite number");
        }
    }

    private void addScalar(tools.jackson.databind.node.ArrayNode array, Object value) {
        if (value == null) {
            array.addNull();
        } else if (value instanceof String text) {
            array.add(text);
        } else if (value instanceof Integer number) {
            array.add(number);
        } else if (value instanceof Long number) {
            array.add(number);
        } else if (value instanceof Float number) {
            array.add(number);
        } else if (value instanceof Double number) {
            array.add(number);
        } else if (value instanceof Boolean bool) {
            array.add(bool);
        } else if (value instanceof Number number) {
            array.add(number.doubleValue());
        } else {
            throw new BizException("qdrant payload filter value must be scalar");
        }
    }

    private String qdrantRangeOperator(String op) {
        return switch (op) {
            case "GT" -> "gt";
            case "GTE" -> "gte";
            case "LT" -> "lt";
            case "LTE" -> "lte";
            default -> throw new BizException("unsupported range op: " + op);
        };
    }

    private SearchPointsResult parseSearchResult(JsonNode response) {
        if (response == null) {
            throw new BizException("qdrant search returned empty response");
        }
        JsonNode result = response.path("result");
        if (!result.isArray()) {
            throw new BizException("qdrant search returned invalid response");
        }
        List<SearchPointHit> hits = new ArrayList<>();
        for (JsonNode hit : result.values()) {
            Object pointId = treeToObject(hit.path("id"), "qdrant search hit id");
            double score = hit.path("score").asDouble();
            if (!Double.isFinite(score)) {
                throw new BizException("qdrant search hit score is invalid");
            }
            hits.add(new SearchPointHit(pointId, score, payloadMap(hit.path("payload"))));
        }
        return new SearchPointsResult(hits);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadMap(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return Map.of();
        }
        Object value = treeToObject(payload, "qdrant search payload");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        throw new BizException("qdrant search payload is invalid");
    }

    private Object treeToObject(JsonNode node, String fieldName) {
        try {
            return objectMapper.treeToValue(node, Object.class);
        } catch (Exception ex) {
            throw new BizException(fieldName + " cannot be decoded", ex);
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
        return switch (normalizeDistanceMetric(metric)) {
            case "COSINE" -> "Cosine";
            case "EUCLID" -> "Euclid";
            case "DOT" -> "Dot";
            default -> throw new BizException("unsupported distance metric: " + metric);
        };
    }

    private String normalizeDistanceMetric(String metric) {
        if (metric == null || metric.isBlank()) {
            throw new BizException("distance metric must not be blank");
        }
        return switch (metric.trim().toUpperCase(Locale.ROOT)) {
            case "COSINE" -> "COSINE";
            case "EUCLID", "EUCLIDEAN", "L2" -> "EUCLID";
            case "DOT", "IP" -> "DOT";
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

    private record QdrantCollectionConfig(VectorConfig unnamedVector, Map<String, VectorConfig> namedVectors) {
    }

    private record VectorConfig(int size, String distance) {
    }
}
