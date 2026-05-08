package com.krystal.vectorsidecarservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(statements = {
        "DROP TABLE IF EXISTS PUBLIC.DOC_INSERT",
        "DELETE FROM SYS_VECTOR_SYNC_ERRORS_",
        "DELETE FROM SYS_VECTOR_SYNC_PROGRESS_",
        "DELETE FROM SYS_VECTOR_SYNC_JOBS_",
        "DELETE FROM SYS_VECTOR_PAYLOAD_FIELDS_",
        "DELETE FROM SYS_VECTOR_OUTBOX_EVENTS_",
        "DELETE FROM SYS_VECTOR_INDEXES_",
        "DELETE FROM SYS_VECTOR_COLLECTIONS_",
        "DELETE FROM SYS_VECTOR_COLUMNS_"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VectorDataControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldInsertRelationalRowAndEnqueueOutboxEvent() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);

        String insertPayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "vectorColumn": "embedding",
                  "pk": 100,
                  "vector": [0.1, 0.2, 0.3],
                  "payload": {
                    "docType": "news"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(insertPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.columnId").value(columnId))
                .andExpect(jsonPath("$.data.tableName").value("DOC_INSERT"))
                .andExpect(jsonPath("$.data.vectorColumn").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.pointId").value("100"))
                .andExpect(jsonPath("$.data.outboxEventId").isNumber())
                .andExpect(jsonPath("$.data.relationalInserted").value(true))
                .andExpect(jsonPath("$.data.vectorInserted").value(true))
                .andExpect(jsonPath("$.data.vectorUpsertStatus").value("PENDING_OUTBOX"));

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PUBLIC.DOC_INSERT WHERE ID = 100 AND DOC_TYPE = 'news'",
                Integer.class
        );
        assertThat(rowCount).isEqualTo(1);

        byte[] vectorBytes = jdbcTemplate.queryForObject(
                "SELECT EMBEDDING FROM PUBLIC.DOC_INSERT WHERE ID = 100",
                byte[].class
        );
        assertThat(vectorBytes).hasSize(12);

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE COLUMN_ID = ? AND SOURCE_PK = '100' AND EVENT_STATUS = 'PENDING'",
                Integer.class,
                columnId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldInsertVectorOnlyRelationalRowAndEnqueueOutboxEvent() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);

        String insertPayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "vectorColumn": "embedding",
                  "pk": 103,
                  "vector": [0.4, 0.5, 0.6]
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(insertPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.columnId").value(columnId))
                .andExpect(jsonPath("$.data.tableName").value("DOC_INSERT"))
                .andExpect(jsonPath("$.data.vectorColumn").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.pointId").value("103"))
                .andExpect(jsonPath("$.data.outboxEventId").isNumber())
                .andExpect(jsonPath("$.data.relationalInserted").value(true))
                .andExpect(jsonPath("$.data.vectorInserted").value(true))
                .andExpect(jsonPath("$.data.vectorUpsertStatus").value("PENDING_OUTBOX"));

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PUBLIC.DOC_INSERT WHERE ID = 103 AND DOC_TYPE IS NULL",
                Integer.class
        );
        assertThat(rowCount).isEqualTo(1);

        byte[] vectorBytes = jdbcTemplate.queryForObject(
                "SELECT EMBEDDING FROM PUBLIC.DOC_INSERT WHERE ID = 103",
                byte[].class
        );
        assertThat(vectorBytes).hasSize(12);

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE COLUMN_ID = ? AND SOURCE_PK = '103' AND EVENT_STATUS = 'PENDING'",
                Integer.class,
                columnId
        );
        assertThat(outboxCount).isEqualTo(1);
    }

    @Test
    void shouldInsertScalarOnlyRelationalRowWithoutVectorColumnInRequest() throws Exception {
        long columnId = createVectorTable();
        registerPayloadField(columnId);

        String insertPayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "pk": 102,
                  "payload": {
                    "docType": "scalar"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(insertPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.columnId").value(columnId))
                .andExpect(jsonPath("$.data.tableName").value("DOC_INSERT"))
                .andExpect(jsonPath("$.data.vectorColumn").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.outboxEventId").doesNotExist())
                .andExpect(jsonPath("$.data.relationalInserted").value(true))
                .andExpect(jsonPath("$.data.vectorInserted").value(false))
                .andExpect(jsonPath("$.data.vectorUpsertStatus").value("SKIPPED_SCALAR_ONLY"));

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PUBLIC.DOC_INSERT WHERE ID = 102 AND DOC_TYPE = 'scalar'",
                Integer.class
        );
        assertThat(rowCount).isEqualTo(1);

        byte[] vectorBytes = jdbcTemplate.queryForObject(
                "SELECT EMBEDDING FROM PUBLIC.DOC_INSERT WHERE ID = 102",
                byte[].class
        );
        assertThat(vectorBytes).isNull();
    }

    @Test
    void shouldRejectUnknownPayloadKey() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);

        String insertPayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "vectorColumn": "embedding",
                  "pk": 101,
                  "vector": [0.1, 0.2, 0.3],
                  "payload": {
                    "unknown": "news"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(insertPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("payload key is not registered or not active: unknown"));
    }

    private long createVectorTable() throws Exception {
        String payload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "ifNotExists": true,
                  "primaryKey": {
                    "name": "id",
                    "type": "bigint"
                  },
                  "scalarColumns": [
                    {
                      "name": "doc_type",
                      "type": "varchar",
                      "length": 50,
                      "nullable": true
                    }
                  ],
                  "vectorColumn": {
                    "name": "embedding",
                    "dimension": 3,
                    "elementType": "float32",
                    "metricType": "cosine",
                    "syncMode": "full_and_incremental"
                  }
                }
                """;

        String responseBody = mockMvc.perform(post("/api/v1/vector-schemas/tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return getDataLong(responseBody, "columnId");
    }

    private void registerReadyCollection(long columnId) throws Exception {
        String payload = """
                {
                  "columnId": %d,
                  "engineType": "QDRANT",
                  "namespaceName": "tenant_insert",
                  "collectionName": "DOC_INSERT_EMBEDDING_V1",
                  "aliasName": "DOC_INSERT_EMBEDDING_ACTIVE",
                  "collectionVersion": "v1",
                  "qdrantVectorName": "default",
                  "vectorDim": 3,
                  "distanceMetric": "cosine",
                  "qdrantIdType": "uint64",
                  "servingState": "ACTIVE",
                  "collectionStatus": "READY"
                }
                """.formatted(columnId);

        mockMvc.perform(post("/api/v1/vector-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private void registerPayloadField(long columnId) throws Exception {
        String payload = """
                {
                  "columnId": %d,
                  "sourceColumnName": "DOC_TYPE",
                  "payloadKey": "docType",
                  "fieldType": "KEYWORD",
                  "syncEnabled": "Y",
                  "fieldStatus": "ACTIVE"
                }
                """.formatted(columnId);

        mockMvc.perform(post("/api/v1/vector-payload-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private long getDataLong(String body, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Cannot find numeric field in response: " + field);
        }
        return Long.parseLong(matcher.group(1));
    }
}
