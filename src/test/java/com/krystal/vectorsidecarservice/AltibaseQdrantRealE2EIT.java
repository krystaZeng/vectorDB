package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.data.VectorOutboxWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("real-e2e")
@EnabledIfEnvironmentVariable(named = "SIDECAR_REAL_E2E", matches = "true")
@SpringBootTest(properties = {
        "vector.engine.qdrant.enabled=true",
        "vector.outbox.worker.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("altibase")
class AltibaseQdrantRealE2EIT {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final String TENANT_ID = "tenant_real_e2e";
    private static final String VECTOR_COLUMN = "EMBEDDING";
    private static final String PAYLOAD_COLUMN = "DOC_TYPE";
    private static final String PAYLOAD_KEY = "docType";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VectorOutboxWorker vectorOutboxWorker;

    @Autowired
    private Environment springEnvironment;

    private final List<Target> targets = new ArrayList<>();
    private final RestClient qdrant = RestClient.builder()
            .baseUrl(env("SIDECAR_QDRANT_URL", "http://127.0.0.1:6333"))
            .build();

    @AfterEach
    void cleanUpRealResources() {
        for (int index = targets.size() - 1; index >= 0; index--) {
            Target target = targets.get(index);
            deleteQdrantCollection(target.collectionName());
            dropBusinessTable(target);
            deleteSidecarRows(target);
        }
        targets.clear();
    }

    @Test
    void shouldCreateAltibaseTableAndQdrantResources() throws Exception {
        Target target = newTarget();
        long columnId = createVectorTable(target);

        assertThat(countRows(target)).isZero();
        assertColumnStatus(columnId, "ACTIVE");
        assertCollectionStatus(columnId, "ACTIVE", "READY");
        assertIndexStatus(columnId, "ONLINE", "READY");
        assertQdrantCollection(target.collectionName(), 3, "Cosine");
        assertQdrantAlias(target.aliasName(), target.collectionName());
    }

    @Test
    void shouldSyncInsertUpdateDeleteToRealQdrantThroughOutbox() throws Exception {
        Target target = newTarget();
        long columnId = createVectorTable(target);
        registerPayloadField(columnId);

        long insertEventId = insertVectorRow(target, 1001, "alpha", "0.1, 0.2, 0.3");
        assertThat(vectorOutboxWorker.drainOnce(10)).isEqualTo(1);
        assertOutboxDone(insertEventId);
        assertBusinessRow(target, 1001, "alpha", 1L);
        assertQdrantPointPayload(target.collectionName(), 1001, "alpha", 1L);

        long updateEventId = updateVectorRow(target, 1001, "beta", "0.7, 0.8, 0.9");
        assertThat(vectorOutboxWorker.drainOnce(10)).isEqualTo(1);
        assertOutboxDone(updateEventId);
        assertBusinessRow(target, 1001, "beta", 2L);
        assertQdrantPointPayload(target.collectionName(), 1001, "beta", 2L);

        long deleteEventId = deleteVectorRow(target, 1001);
        assertThat(vectorOutboxWorker.drainOnce(10)).isEqualTo(1);
        assertOutboxDone(deleteEventId);
        assertThat(countRows(target)).isZero();
        assertQdrantPointMissing(target.collectionName(), 1001);
    }

    private Target newTarget() {
        String schemaName = normalizeIdentifier(env(
                "SIDECAR_E2E_SCHEMA",
                springEnvironment.getProperty("spring.datasource.username", "SYS")
        ));
        String suffix = Long.toString(Instant.now().toEpochMilli());
        String tableName = normalizeIdentifier("E2E_DOC_" + suffix);
        Target target = new Target(
                schemaName,
                tableName,
                tableName + "_" + VECTOR_COLUMN + "_V1",
                tableName + "_" + VECTOR_COLUMN + "_ACTIVE"
        );
        targets.add(target);
        return target;
    }

    private long createVectorTable(Target target) throws Exception {
        String payload = """
                {
                  "tenantId": "%s",
                  "schemaName": "%s",
                  "tableName": "%s",
                  "ifNotExists": true,
                  "autoRegisterCollection": true,
                  "autoRegisterIndex": true,
                  "defaultIndexProfileName": "default",
                  "primaryKey": {
                    "name": "id",
                    "type": "bigint"
                  },
                  "scalarColumns": [
                    {
                      "name": "%s",
                      "type": "varchar",
                      "length": 50,
                      "nullable": true
                    }
                  ],
                  "vectorColumn": {
                    "name": "%s",
                    "dimension": 3,
                    "elementType": "float32",
                    "metricType": "cosine",
                    "syncMode": "full_and_incremental"
                  }
                }
                """.formatted(
                TENANT_ID,
                target.schemaName(),
                target.tableName(),
                PAYLOAD_COLUMN,
                VECTOR_COLUMN
        );

        String responseBody = mockMvc.perform(post("/api/v1/vector-schemas/tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tableName").value(target.tableName()))
                .andExpect(jsonPath("$.data.columnId").isNumber())
                .andExpect(jsonPath("$.data.collectionId").isNumber())
                .andExpect(jsonPath("$.data.indexId").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return dataLong(responseBody, "columnId");
    }

    private void registerPayloadField(long columnId) throws Exception {
        String payload = """
                {
                  "columnId": %d,
                  "sourceColumnName": "%s",
                  "payloadKey": "%s",
                  "fieldType": "KEYWORD",
                  "syncEnabled": "Y",
                  "fieldStatus": "ACTIVE"
                }
                """.formatted(columnId, PAYLOAD_COLUMN, PAYLOAD_KEY);

        mockMvc.perform(post("/api/v1/vector-payload-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private long insertVectorRow(Target target, long pk, String docType, String vectorCsv) throws Exception {
        String payload = dataPayload(target, pk, docType, vectorCsv);
        String responseBody = mockMvc.perform(post("/api/v1/vector-data/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vectorUpsertStatus").value("PENDING_OUTBOX"))
                .andExpect(jsonPath("$.data.outboxEventId").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return dataLong(responseBody, "outboxEventId");
    }

    private long updateVectorRow(Target target, long pk, String docType, String vectorCsv) throws Exception {
        String payload = dataPayload(target, pk, docType, vectorCsv);
        String responseBody = mockMvc.perform(post("/api/v1/vector-data/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vectorSyncStatus").value("VECTOR_SYNC_ENQUEUED"))
                .andExpect(jsonPath("$.data.outboxEventId").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return dataLong(responseBody, "outboxEventId");
    }

    private long deleteVectorRow(Target target, long pk) throws Exception {
        String payload = """
                {
                  "tenantId": "%s",
                  "schemaName": "%s",
                  "tableName": "%s",
                  "vectorColumn": "%s",
                  "pk": %d
                }
                """.formatted(TENANT_ID, target.schemaName(), target.tableName(), VECTOR_COLUMN, pk);

        String responseBody = mockMvc.perform(post("/api/v1/vector-data/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vectorSyncStatus").value("PENDING_OUTBOX"))
                .andExpect(jsonPath("$.data.outboxEventId").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return dataLong(responseBody, "outboxEventId");
    }

    private String dataPayload(Target target, long pk, String docType, String vectorCsv) {
        return """
                {
                  "tenantId": "%s",
                  "schemaName": "%s",
                  "tableName": "%s",
                  "vectorColumn": "%s",
                  "pk": %d,
                  "vector": [%s],
                  "payload": {
                    "%s": "%s"
                  }
                }
                """.formatted(
                TENANT_ID,
                target.schemaName(),
                target.tableName(),
                VECTOR_COLUMN,
                pk,
                vectorCsv,
                PAYLOAD_KEY,
                docType
        );
    }

    private void assertColumnStatus(long columnId, String status) {
        String actual = jdbcTemplate.queryForObject(
                "SELECT STATUS FROM SYS_VECTOR_COLUMNS_ WHERE COLUMN_ID = ?",
                String.class,
                columnId
        );
        assertThat(actual).isEqualTo(status);
    }

    private void assertCollectionStatus(long columnId, String servingState, String collectionStatus) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                        SELECT SERVING_STATE, COLLECTION_STATUS
                        FROM SYS_VECTOR_COLLECTIONS_
                        WHERE COLUMN_ID = ?
                        """,
                columnId
        );
        assertThat(row.get("SERVING_STATE")).isEqualTo(servingState);
        assertThat(row.get("COLLECTION_STATUS")).isEqualTo(collectionStatus);
    }

    private void assertIndexStatus(long columnId, String servingState, String indexStatus) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                        SELECT SERVING_STATE, INDEX_STATUS
                        FROM SYS_VECTOR_INDEXES_
                        WHERE COLUMN_ID = ?
                        """,
                columnId
        );
        assertThat(row.get("SERVING_STATE")).isEqualTo(servingState);
        assertThat(row.get("INDEX_STATUS")).isEqualTo(indexStatus);
    }

    private void assertBusinessRow(Target target, long pk, String docType, long rowVersion) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT DOC_TYPE, ROW_VERSION, EMBEDDING FROM " + qualifiedTable(target) + " WHERE ID = ?",
                pk
        );
        assertThat(row.get("DOC_TYPE")).isEqualTo(docType);
        assertThat(((Number) row.get("ROW_VERSION")).longValue()).isEqualTo(rowVersion);
        assertThat((byte[]) row.get("EMBEDDING")).hasSize(12);
    }

    private void assertOutboxDone(long eventId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT EVENT_STATUS, ACTIVE_KEY FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                eventId
        );
        assertThat(row.get("EVENT_STATUS")).isEqualTo("DONE");
        assertThat(row.get("ACTIVE_KEY")).isNull();
    }

    private void assertQdrantCollection(String collectionName, int expectedSize, String expectedDistance) {
        JsonNode vectors = qdrantGet("/collections/{collection}", collectionName)
                .path("result")
                .path("config")
                .path("params")
                .path("vectors");
        assertThat(vectors.path("size").asInt()).isEqualTo(expectedSize);
        assertThat(vectors.path("distance").asString()).isEqualTo(expectedDistance);
    }

    private void assertQdrantAlias(String aliasName, String collectionName) {
        JsonNode aliases = qdrantGet("/aliases").path("result").path("aliases");
        assertThat(aliases.isArray()).isTrue();
        for (JsonNode alias : aliases.values()) {
            if (aliasName.equals(alias.path("alias_name").asString())) {
                assertThat(alias.path("collection_name").asString()).isEqualTo(collectionName);
                return;
            }
        }
        throw new AssertionError("qdrant alias not found: " + aliasName);
    }

    private void assertQdrantPointPayload(String collectionName, long pointId, String docType, long sourceVersion) {
        JsonNode result = retrievePoint(collectionName, pointId).path("result");
        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isEqualTo(1);

        JsonNode payload = result.get(0).path("payload");
        assertThat(payload.path(PAYLOAD_KEY).asString()).isEqualTo(docType);
        assertThat(payload.path("_sidecar_source_version").asLong()).isEqualTo(sourceVersion);
    }

    private void assertQdrantPointMissing(String collectionName, long pointId) {
        JsonNode result = retrievePoint(collectionName, pointId).path("result");
        assertThat(result.isArray()).isTrue();
        assertThat(result.size()).isZero();
    }

    private JsonNode retrievePoint(String collectionName, long pointId) {
        return qdrant.post()
                .uri("/collections/{collection}/points", collectionName)
                .headers(this::applyQdrantApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "ids", List.of(pointId),
                        "with_payload", true,
                        "with_vector", true
                ))
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode qdrantGet(String uri, Object... uriVariables) {
        return qdrant.get()
                .uri(uri, uriVariables)
                .headers(this::applyQdrantApiKey)
                .retrieve()
                .body(JsonNode.class);
    }

    private void deleteQdrantCollection(String collectionName) {
        try {
            qdrant.delete()
                    .uri("/collections/{collection}", collectionName)
                    .headers(this::applyQdrantApiKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw ex;
            }
        }
    }

    private void dropBusinessTable(Target target) {
        try {
            jdbcTemplate.execute("DROP TABLE " + qualifiedTable(target));
        } catch (Exception ignored) {
            // The test may fail before the business table is created.
        }
    }

    private void deleteSidecarRows(Target target) {
        List<Long> columnIds = jdbcTemplate.queryForList(
                """
                        SELECT COLUMN_ID
                        FROM SYS_VECTOR_COLUMNS_
                        WHERE TENANT_ID = ?
                          AND SCHEMA_NAME = ?
                          AND TABLE_NAME = ?
                        """,
                Long.class,
                TENANT_ID.toUpperCase(Locale.ROOT),
                target.schemaName(),
                target.tableName()
        );
        for (Long columnId : columnIds) {
            jdbcTemplate.update("DELETE FROM SYS_VECTOR_SYNC_ERRORS_ WHERE COLUMN_ID = ?", columnId);
            jdbcTemplate.update("DELETE FROM SYS_VECTOR_SYNC_PROGRESS_ WHERE COLUMN_ID = ?", columnId);
            jdbcTemplate.update("DELETE FROM SYS_VECTOR_SYNC_JOBS_ WHERE COLUMN_ID = ?", columnId);
            jdbcTemplate.update("DELETE FROM SYS_VECTOR_PAYLOAD_FIELDS_ WHERE COLUMN_ID = ?", columnId);
            jdbcTemplate.update("DELETE FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE COLUMN_ID = ?", columnId);
            jdbcTemplate.update("DELETE FROM SYS_VECTOR_SOURCE_VERSIONS_ WHERE COLUMN_ID = ?", columnId);
            jdbcTemplate.update("DELETE FROM SYS_VECTOR_INDEXES_ WHERE COLUMN_ID = ?", columnId);
            jdbcTemplate.update("DELETE FROM SYS_VECTOR_COLLECTIONS_ WHERE COLUMN_ID = ?", columnId);
            jdbcTemplate.update("DELETE FROM SYS_VECTOR_COLUMNS_ WHERE COLUMN_ID = ?", columnId);
        }
    }

    private int countRows(Target target) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + qualifiedTable(target),
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private String qualifiedTable(Target target) {
        return target.schemaName() + "." + target.tableName();
    }

    private void applyQdrantApiKey(HttpHeaders headers) {
        String apiKey = env("SIDECAR_QDRANT_API_KEY", "");
        if (!apiKey.isBlank()) {
            headers.add("api-key", apiKey);
        }
    }

    private long dataLong(String body, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Cannot find numeric field in response: " + field);
        }
        return Long.parseLong(matcher.group(1));
    }

    private String normalizeIdentifier(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid E2E identifier: " + value);
        }
        return normalized;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private record Target(
            String schemaName,
            String tableName,
            String collectionName,
            String aliasName
    ) {
    }
}
