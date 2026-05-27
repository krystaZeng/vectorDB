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
        "DROP TABLE IF EXISTS PUBLIC.DOC",
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
class VectorRegistryControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRegisterAndListVectorColumn() throws Exception {
        createDocTable();
        String payload = """
                {
                  \"tenantId\": \"tenant_a\",
                  \"schemaName\": \"public\",
                  \"tableName\": \"doc\",
                  \"pkColumn\": \"id\",
                  \"vectorColumn\": \"embedding\",
                  \"dimension\": 768,
                  \"metricType\": \"cosine\",
                  \"syncMode\": \"full_and_incremental\"
                }
                """;

        mockMvc.perform(post("/api/v1/vector-columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value("TENANT_A"))
                .andExpect(jsonPath("$.data.tableName").value("DOC"))
                .andExpect(jsonPath("$.data.metricType").value("COSINE"))
                .andExpect(jsonPath("$.data.status").value("BUILDING"))
                .andExpect(jsonPath("$.data.syncMode").value("FULL_AND_INCREMENTAL"))
                .andExpect(jsonPath("$.data.definitionHash").isNotEmpty());

        mockMvc.perform(get("/api/v1/vector-columns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].tableName").value("DOC"))
                .andExpect(jsonPath("$.data[0].vectorColumn").value("EMBEDDING"));
    }

    @Test
    void shouldRejectDuplicateVectorColumnRegistration() throws Exception {
        createDocTable();
        String payload = """
                {
                  \"tenantId\": \"tenant_a\",
                  \"schemaName\": \"public\",
                  \"tableName\": \"doc\",
                  \"pkColumn\": \"id\",
                  \"vectorColumn\": \"embedding\",
                  \"dimension\": 768,
                  \"metricType\": \"cosine\"
                }
                """;

        mockMvc.perform(post("/api/v1/vector-columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/vector-columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("vector column already registered"));
    }

    @Test
    void shouldRejectInvalidIdentifierWhenRegisteringVectorColumn() throws Exception {
        String payload = """
                {
                  \"tenantId\": \"tenant_a\",
                  \"schemaName\": \"public\",
                  \"tableName\": \"doc-name\",
                  \"pkColumn\": \"id\",
                  \"vectorColumn\": \"embedding\",
                  \"dimension\": 768,
                  \"metricType\": \"cosine\"
                }
                """;

        mockMvc.perform(post("/api/v1/vector-columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("tableName must match [A-Za-z][A-Za-z0-9_]*"));
    }

    @Test
    void shouldRejectMissingRelationalTableWhenRegisteringVectorColumn() throws Exception {
        String payload = """
                {
                  \"tenantId\": \"tenant_a\",
                  \"schemaName\": \"public\",
                  \"tableName\": \"doc\",
                  \"pkColumn\": \"id\",
                  \"vectorColumn\": \"embedding\",
                  \"dimension\": 768,
                  \"metricType\": \"cosine\"
                }
                """;

        mockMvc.perform(post("/api/v1/vector-columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("table does not exist: PUBLIC.DOC"));
    }

    @Test
    void shouldRejectDirectActiveStatusWhenRegisteringVectorColumn() throws Exception {
        createDocTable();
        String payload = """
                {
                  \"tenantId\": \"tenant_a\",
                  \"schemaName\": \"public\",
                  \"tableName\": \"doc\",
                  \"pkColumn\": \"id\",
                  \"vectorColumn\": \"embedding\",
                  \"dimension\": 768,
                  \"metricType\": \"cosine\",
                  \"status\": \"active\"
                }
                """;

        mockMvc.perform(post("/api/v1/vector-columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("manual vector column registration cannot create ACTIVE column; use verify-and-activate"));
    }

    @Test
    void shouldVerifyAndActivateRegisteredVectorColumn() throws Exception {
        createDocTable();
        long columnId = registerBuildingColumn();
        registerReadyCollection(columnId);

        mockMvc.perform(post("/api/v1/vector-columns/{columnId}/verify-and-activate", columnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.columnId").value(columnId))
                .andExpect(jsonPath("$.data.previousStatus").value("BUILDING"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.activated").value(true));

        String status = jdbcTemplate.queryForObject(
                "SELECT STATUS FROM SYS_VECTOR_COLUMNS_ WHERE COLUMN_ID = ?",
                String.class,
                columnId
        );
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    void shouldMarkColumnFailedWhenActivationCannotFindReadyCollection() throws Exception {
        createDocTable();
        long columnId = registerBuildingColumn();

        mockMvc.perform(post("/api/v1/vector-columns/{columnId}/verify-and-activate", columnId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("ready vector collection not found for column: " + columnId));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT STATUS, REMARK FROM SYS_VECTOR_COLUMNS_ WHERE COLUMN_ID = ?",
                columnId
        );
        assertThat(row.get("STATUS")).isEqualTo("FAILED");
        assertThat(row.get("REMARK")).isEqualTo("ready vector collection not found for column: " + columnId);
    }

    private void createDocTable() {
        jdbcTemplate.execute("""
                CREATE TABLE PUBLIC.DOC (
                    ID BIGINT NOT NULL,
                    EMBEDDING VARBINARY(3072),
                    PRIMARY KEY (ID)
                )
                """);
    }

    private long registerBuildingColumn() throws Exception {
        String payload = """
                {
                  \"tenantId\": \"tenant_a\",
                  \"schemaName\": \"public\",
                  \"tableName\": \"doc\",
                  \"pkColumn\": \"id\",
                  \"vectorColumn\": \"embedding\",
                  \"dimension\": 768,
                  \"metricType\": \"cosine\"
                }
                """;

        String body = mockMvc.perform(post("/api/v1/vector-columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BUILDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return getDataLong(body, "columnId");
    }

    private void registerReadyCollection(long columnId) throws Exception {
        String payload = """
                {
                  \"columnId\": %d,
                  \"engineType\": \"QDRANT\",
                  \"namespaceName\": \"tenant_a\",
                  \"collectionName\": \"DOC_EMBEDDING_V1\",
                  \"aliasName\": null,
                  \"collectionVersion\": \"v1\",
                  \"qdrantVectorName\": \"default\",
                  \"vectorDim\": 768,
                  \"distanceMetric\": \"cosine\",
                  \"qdrantIdType\": \"uint64\",
                  \"servingState\": \"ACTIVE\",
                  \"collectionStatus\": \"READY\"
                }
                """.formatted(columnId);

        mockMvc.perform(post("/api/v1/vector-collections")
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
