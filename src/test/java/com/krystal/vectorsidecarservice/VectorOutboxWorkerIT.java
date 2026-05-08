package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.data.VectorOutboxWorker;
import com.krystal.vectorsidecarservice.application.port.out.VectorEngineDataPort;
import com.krystal.vectorsidecarservice.application.system.VectorEngineDataRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(statements = {
        "DROP TABLE IF EXISTS PUBLIC.DOC_OUTBOX",
        "DELETE FROM SYS_VECTOR_SYNC_ERRORS_",
        "DELETE FROM SYS_VECTOR_SYNC_PROGRESS_",
        "DELETE FROM SYS_VECTOR_SYNC_JOBS_",
        "DELETE FROM SYS_VECTOR_PAYLOAD_FIELDS_",
        "DELETE FROM SYS_VECTOR_OUTBOX_EVENTS_",
        "DELETE FROM SYS_VECTOR_INDEXES_",
        "DELETE FROM SYS_VECTOR_COLLECTIONS_",
        "DELETE FROM SYS_VECTOR_COLUMNS_"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VectorOutboxWorkerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private VectorOutboxWorker vectorOutboxWorker;

    @Autowired
    private TestVectorEngineDataPort testVectorEngineDataPort;

    @BeforeEach
    void resetVectorEngine() {
        testVectorEngineDataPort.reset();
    }

    @Test
    void shouldDrainPendingOutboxEventAndUpsertQdrantPoint() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();

        mockMvc.perform(get("/api/v1/vector-outbox-events")
                        .param("status", "PENDING")
                        .param("columnId", String.valueOf(columnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].eventId").value(eventId))
                .andExpect(jsonPath("$.data[0].eventStatus").value("PENDING"));

        int processed = vectorOutboxWorker.drainOnce(10);

        assertThat(processed).isEqualTo(1);
        String eventStatus = jdbcTemplate.queryForObject(
                "SELECT EVENT_STATUS FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                String.class,
                eventId
        );
        assertThat(eventStatus).isEqualTo("DONE");

        VectorEngineDataPort.UpsertPointCommand command = testVectorEngineDataPort.lastCommand();
        assertThat(command).isNotNull();
        assertThat(command.collectionName()).isEqualTo("DOC_OUTBOX_EMBEDDING_ACTIVE");
        assertThat(command.vectorName()).isEqualTo("default");
        assertThat(command.pointId()).isEqualTo(200L);
        assertThat(command.vector()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(command.payload()).containsEntry("docType", "news");
    }

    @Test
    void shouldRetryOutboxEventWhenVectorEngineFails() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();
        testVectorEngineDataPort.failNext("mock qdrant timeout");

        int processed = vectorOutboxWorker.drainOnce(10);

        assertThat(processed).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT EVENT_STATUS, RETRY_COUNT, ERROR_MESSAGE FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                eventId
        );
        assertThat(row.get("EVENT_STATUS")).isEqualTo("RETRYING");
        assertThat(((Number) row.get("RETRY_COUNT")).intValue()).isEqualTo(1);
        assertThat(row.get("ERROR_MESSAGE")).asString().contains("mock qdrant timeout");
    }

    private long createVectorTable() throws Exception {
        String payload = """
                {
                  "tenantId": "tenant_outbox",
                  "schemaName": "public",
                  "tableName": "doc_outbox",
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
                  "namespaceName": "tenant_outbox",
                  "collectionName": "DOC_OUTBOX_EMBEDDING_V1",
                  "aliasName": "DOC_OUTBOX_EMBEDDING_ACTIVE",
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

    private long insertVectorRow() throws Exception {
        String insertPayload = """
                {
                  "tenantId": "tenant_outbox",
                  "schemaName": "public",
                  "tableName": "doc_outbox",
                  "vectorColumn": "embedding",
                  "pk": 200,
                  "vector": [0.1, 0.2, 0.3],
                  "payload": {
                    "docType": "news"
                  }
                }
                """;

        String responseBody = mockMvc.perform(post("/api/v1/vector-data/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(insertPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vectorUpsertStatus").value("PENDING_OUTBOX"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return getDataLong(responseBody, "outboxEventId");
    }

    private long getDataLong(String body, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Cannot find numeric field in response: " + field);
        }
        return Long.parseLong(matcher.group(1));
    }

    @TestConfiguration
    static class OutboxWorkerTestConfiguration {

        @Bean
        TestVectorEngineDataPort testVectorEngineDataPort() {
            return new TestVectorEngineDataPort();
        }

        @Bean
        @Primary
        VectorEngineDataRouter vectorEngineDataRouter(TestVectorEngineDataPort testVectorEngineDataPort) {
            return new VectorEngineDataRouter(List.of(testVectorEngineDataPort));
        }
    }

    static class TestVectorEngineDataPort implements VectorEngineDataPort {

        private volatile UpsertPointCommand lastCommand;
        private volatile String nextFailureMessage;

        @Override
        public String engineType() {
            return "QDRANT";
        }

        @Override
        public UpsertPointResult upsertPoint(UpsertPointCommand command) {
            if (nextFailureMessage != null) {
                String message = nextFailureMessage;
                nextFailureMessage = null;
                throw new RuntimeException(message);
            }
            this.lastCommand = command;
            return UpsertPointResult.upserted("point upserted");
        }

        void failNext(String message) {
            this.nextFailureMessage = message;
        }

        VectorEngineDataPort.UpsertPointCommand lastCommand() {
            return lastCommand;
        }

        void reset() {
            this.lastCommand = null;
            this.nextFailureMessage = null;
        }
    }
}
