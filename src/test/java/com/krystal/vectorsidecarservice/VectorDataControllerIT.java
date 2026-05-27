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

import java.util.List;
import java.util.Map;
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
        "DELETE FROM SYS_VECTOR_SOURCE_VERSIONS_",
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
    void shouldUpdateScalarOnlyRowAndSkipVectorSyncWhenVectorIsAbsent() throws Exception {
        long columnId = createVectorTable();
        registerPayloadField(columnId);

        String insertPayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "pk": 105,
                  "payload": {
                    "docType": "draft"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(insertPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        String updatePayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "pk": 105,
                  "payload": {
                    "docType": "published"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sourceVersion").value(2))
                .andExpect(jsonPath("$.data.relationalUpdated").value(true))
                .andExpect(jsonPath("$.data.vectorSyncEnqueued").value(false))
                .andExpect(jsonPath("$.data.vectorSyncStatus")
                        .value("RELATIONAL_UPDATED_VECTOR_SYNC_SKIPPED_NO_VECTOR"))
                .andExpect(jsonPath("$.data.outboxEventId").doesNotExist());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT DOC_TYPE, ROW_VERSION, EMBEDDING FROM PUBLIC.DOC_INSERT WHERE ID = 105"
        );
        assertThat(row.get("DOC_TYPE")).isEqualTo("published");
        assertThat(((Number) row.get("ROW_VERSION")).longValue()).isEqualTo(2L);
        assertThat((byte[]) row.get("EMBEDDING")).isNull();

        Integer outboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE COLUMN_ID = ? AND SOURCE_PK = '105'",
                Integer.class,
                columnId
        );
        assertThat(outboxCount).isZero();
    }

    @Test
    void shouldRejectExplicitNullVectorOnUpdate() throws Exception {
        createVectorTable();

        String updatePayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "pk": 106,
                  "vector": null,
                  "payload": {
                    "docType": "null-vector"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("INVALID_VECTOR_VALUE: vector must not be null"));
    }

    @Test
    void shouldUpdateRelationalRowAndMergeVectorUpsertOutboxEvent() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        insertVectorRow(100, "news", List.of(0.1, 0.2, 0.3));

        String updatePayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "vectorColumn": "embedding",
                  "pk": 100,
                  "vector": [0.7, 0.8, 0.9],
                  "payload": {
                    "docType": "feature"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.columnId").value(columnId))
                .andExpect(jsonPath("$.data.tableName").value("DOC_INSERT"))
                .andExpect(jsonPath("$.data.pointId").value("100"))
                .andExpect(jsonPath("$.data.sourceVersion").value(2))
                .andExpect(jsonPath("$.data.relationalUpdated").value(true))
                .andExpect(jsonPath("$.data.vectorSyncEnqueued").value(true))
                .andExpect(jsonPath("$.data.vectorSyncStatus").value("VECTOR_SYNC_ENQUEUED"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT DOC_TYPE, ROW_VERSION, EMBEDDING FROM PUBLIC.DOC_INSERT WHERE ID = 100"
        );
        assertThat(row.get("DOC_TYPE")).isEqualTo("feature");
        assertThat(((Number) row.get("ROW_VERSION")).longValue()).isEqualTo(2L);
        assertThat((byte[]) row.get("EMBEDDING")).hasSize(12);

        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                """
                        SELECT EVENT_TYPE, SOURCE_OP, SOURCE_VERSION, EVENT_STATUS
                        FROM SYS_VECTOR_OUTBOX_EVENTS_
                        WHERE COLUMN_ID = ? AND SOURCE_PK = '100'
                        """,
                columnId
        );
        assertThat(outbox.get("EVENT_TYPE")).isEqualTo("UPSERT");
        assertThat(outbox.get("SOURCE_OP")).isEqualTo("UPDATE");
        assertThat(((Number) outbox.get("SOURCE_VERSION")).longValue()).isEqualTo(2L);
        assertThat(outbox.get("EVENT_STATUS")).isEqualTo("PENDING");

        Long currentVersion = jdbcTemplate.queryForObject(
                "SELECT CURRENT_VERSION FROM SYS_VECTOR_SOURCE_VERSIONS_ WHERE COLUMN_ID = ? AND SOURCE_PK = '100'",
                Long.class,
                columnId
        );
        assertThat(currentVersion).isEqualTo(2L);
    }

    @Test
    void shouldUpdateSyncPayloadOnlyAndMergeVectorUpsertOutboxEventWhenVectorExists() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        insertVectorRow(107, "news", List.of(0.1, 0.2, 0.3));

        String updatePayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "vectorColumn": "embedding",
                  "pk": 107,
                  "payload": {
                    "docType": "feature"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sourceVersion").value(2))
                .andExpect(jsonPath("$.data.relationalUpdated").value(true))
                .andExpect(jsonPath("$.data.vectorSyncEnqueued").value(true))
                .andExpect(jsonPath("$.data.vectorSyncStatus").value("VECTOR_SYNC_ENQUEUED"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT DOC_TYPE, ROW_VERSION, EMBEDDING FROM PUBLIC.DOC_INSERT WHERE ID = 107"
        );
        assertThat(row.get("DOC_TYPE")).isEqualTo("feature");
        assertThat(((Number) row.get("ROW_VERSION")).longValue()).isEqualTo(2L);
        assertThat((byte[]) row.get("EMBEDDING")).hasSize(12);

        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                """
                        SELECT EVENT_TYPE, SOURCE_OP, SOURCE_VERSION, EVENT_STATUS
                        FROM SYS_VECTOR_OUTBOX_EVENTS_
                        WHERE COLUMN_ID = ? AND SOURCE_PK = '107'
                        """,
                columnId
        );
        assertThat(outbox.get("EVENT_TYPE")).isEqualTo("UPSERT");
        assertThat(outbox.get("SOURCE_OP")).isEqualTo("UPDATE");
        assertThat(((Number) outbox.get("SOURCE_VERSION")).longValue()).isEqualTo(2L);
        assertThat(outbox.get("EVENT_STATUS")).isEqualTo("PENDING");
    }

    @Test
    void shouldDeleteRelationalRowAndMergeVectorDeleteOutboxEvent() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        insertVectorRow(104, "delete-me", List.of(0.1, 0.2, 0.3));

        String deletePayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "vectorColumn": "embedding",
                  "pk": 104
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deletePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.columnId").value(columnId))
                .andExpect(jsonPath("$.data.tableName").value("DOC_INSERT"))
                .andExpect(jsonPath("$.data.pointId").value("104"))
                .andExpect(jsonPath("$.data.sourceVersion").value(2))
                .andExpect(jsonPath("$.data.relationalDeleted").value(true))
                .andExpect(jsonPath("$.data.vectorSyncEnqueued").value(true))
                .andExpect(jsonPath("$.data.vectorSyncStatus").value("PENDING_OUTBOX"));

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PUBLIC.DOC_INSERT WHERE ID = 104",
                Integer.class
        );
        assertThat(rowCount).isZero();

        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                """
                        SELECT EVENT_TYPE, SOURCE_OP, SOURCE_VERSION, EVENT_STATUS, ACTIVE_KEY
                        FROM SYS_VECTOR_OUTBOX_EVENTS_
                        WHERE COLUMN_ID = ? AND SOURCE_PK = '104'
                        """,
                columnId
        );
        assertThat(outbox.get("EVENT_TYPE")).isEqualTo("DELETE");
        assertThat(outbox.get("SOURCE_OP")).isEqualTo("DELETE");
        assertThat(((Number) outbox.get("SOURCE_VERSION")).longValue()).isEqualTo(2L);
        assertThat(outbox.get("EVENT_STATUS")).isEqualTo("PENDING");
        assertThat(outbox.get("ACTIVE_KEY")).isNotNull();

        Long currentVersion = jdbcTemplate.queryForObject(
                "SELECT CURRENT_VERSION FROM SYS_VECTOR_SOURCE_VERSIONS_ WHERE COLUMN_ID = ? AND SOURCE_PK = '104'",
                Long.class,
                columnId
        );
        assertThat(currentVersion).isEqualTo(2L);
    }

    @Test
    void shouldMergeInsertAfterBusinessDeleteIntoLatestVectorUpsertEvent() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        insertVectorRow(202, "first", List.of(0.1, 0.2, 0.3));
        deleteVectorRow(202);

        insertVectorRow(202, "second", List.of(0.4, 0.5, 0.6));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT DOC_TYPE, ROW_VERSION FROM PUBLIC.DOC_INSERT WHERE ID = 202"
        );
        assertThat(row.get("DOC_TYPE")).isEqualTo("second");
        assertThat(((Number) row.get("ROW_VERSION")).longValue()).isEqualTo(3L);

        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                """
                        SELECT EVENT_TYPE, SOURCE_OP, SOURCE_VERSION, EVENT_STATUS
                        FROM SYS_VECTOR_OUTBOX_EVENTS_
                        WHERE COLUMN_ID = ? AND SOURCE_PK = '202'
                        """,
                columnId
        );
        assertThat(outbox.get("EVENT_TYPE")).isEqualTo("UPSERT");
        assertThat(outbox.get("SOURCE_OP")).isEqualTo("INSERT");
        assertThat(((Number) outbox.get("SOURCE_VERSION")).longValue()).isEqualTo(3L);
        assertThat(outbox.get("EVENT_STATUS")).isEqualTo("PENDING");

        Long currentVersion = jdbcTemplate.queryForObject(
                "SELECT CURRENT_VERSION FROM SYS_VECTOR_SOURCE_VERSIONS_ WHERE COLUMN_ID = ? AND SOURCE_PK = '202'",
                Long.class,
                columnId
        );
        assertThat(currentVersion).isEqualTo(3L);
    }

    @Test
    void shouldKeepSourceVersionMonotonicAfterRowIsDeletedAndReinserted() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        insertVectorRow(201, "first", List.of(0.1, 0.2, 0.3));

        int deletedRows = jdbcTemplate.update("DELETE FROM PUBLIC.DOC_INSERT WHERE ID = 201");
        assertThat(deletedRows).isEqualTo(1);

        insertVectorRow(201, "second", List.of(0.4, 0.5, 0.6));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT DOC_TYPE, ROW_VERSION FROM PUBLIC.DOC_INSERT WHERE ID = 201"
        );
        assertThat(row.get("DOC_TYPE")).isEqualTo("second");
        assertThat(((Number) row.get("ROW_VERSION")).longValue()).isEqualTo(2L);

        Long currentVersion = jdbcTemplate.queryForObject(
                "SELECT CURRENT_VERSION FROM SYS_VECTOR_SOURCE_VERSIONS_ WHERE COLUMN_ID = ? AND SOURCE_PK = '201'",
                Long.class,
                columnId
        );
        assertThat(currentVersion).isEqualTo(2L);
    }

    @Test
    void shouldRejectUpdateForMissingRow() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);

        String updatePayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "vectorColumn": "embedding",
                  "pk": 999,
                  "payload": {
                    "docType": "missing"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("row not found for update: 999"));
    }

    @Test
    void shouldRejectDeleteForMissingRow() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);

        String deletePayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "vectorColumn": "embedding",
                  "pk": 999
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deletePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("row not found for delete: 999"));
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

    private void insertVectorRow(long pk, String docType, List<Double> vector) throws Exception {
        String insertPayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "vectorColumn": "embedding",
                  "pk": %d,
                  "vector": [%s],
                  "payload": {
                    "docType": "%s"
                  }
                }
                """.formatted(pk, vectorCsv(vector), docType);

        mockMvc.perform(post("/api/v1/vector-data/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(insertPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private void deleteVectorRow(long pk) throws Exception {
        String deletePayload = """
                {
                  "tenantId": "tenant_insert",
                  "schemaName": "public",
                  "tableName": "doc_insert",
                  "vectorColumn": "embedding",
                  "pk": %d
                }
                """.formatted(pk);

        mockMvc.perform(post("/api/v1/vector-data/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deletePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private String vectorCsv(List<Double> vector) {
        return vector.stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
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
