package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.port.out.VectorEngineAdminPort;
import com.krystal.vectorsidecarservice.application.system.VectorEngineAdminRouter;
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
        "DROP TABLE IF EXISTS PUBLIC.DOC_READY",
        "DROP TABLE IF EXISTS PUBLIC.DOC_FAIL",
        "DELETE FROM SYS_VECTOR_INDEXES_",
        "DELETE FROM SYS_VECTOR_COLLECTIONS_",
        "DELETE FROM SYS_VECTOR_COLUMNS_"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VectorSchemaProvisioningIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestVectorEngineAdminPort testVectorEngineAdminPort;

    @BeforeEach
    void resetProvisioningMode() {
        testVectorEngineAdminPort.setMode(TestVectorEngineAdminPort.Mode.SUCCESS);
    }

    @Test
    void shouldMarkCollectionAndIndexReadyWhenProvisioningSucceeds() throws Exception {
        String payload = """
                {
                  "tenantId": "tenant_ready",
                  "schemaName": "public",
                  "tableName": "doc_ready",
                  "ifNotExists": true,
                  "autoRegisterCollection": true,
                  "autoRegisterIndex": true,
                  "defaultIndexProfileName": "ready_profile",
                  "primaryKey": {
                    "name": "id",
                    "type": "bigint"
                  },
                  "scalarColumns": [
                    {
                      "name": "title",
                      "type": "varchar",
                      "length": 200,
                      "nullable": true
                    }
                  ],
                  "vectorColumn": {
                    "name": "embedding",
                    "dimension": 768,
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
                .andExpect(jsonPath("$.data.tableName").value("DOC_READY"))
                .andExpect(jsonPath("$.data.collectionId").isNumber())
                .andExpect(jsonPath("$.data.indexId").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long columnId = getDataLong(responseBody, "columnId");

        mockMvc.perform(get("/api/v1/vector-collections").param("columnId", String.valueOf(columnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].servingState").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].collectionStatus").value("READY"));

        mockMvc.perform(get("/api/v1/vector-indexes").param("columnId", String.valueOf(columnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].servingState").value("ONLINE"))
                .andExpect(jsonPath("$.data[0].indexStatus").value("READY"));
    }

    @Test
    void shouldMarkCollectionAndIndexFailedWhenProvisioningFails() throws Exception {
        testVectorEngineAdminPort.setMode(TestVectorEngineAdminPort.Mode.FAIL_COLLECTION);

        String payload = """
                {
                  "tenantId": "tenant_fail",
                  "schemaName": "public",
                  "tableName": "doc_fail",
                  "ifNotExists": true,
                  "autoRegisterCollection": true,
                  "autoRegisterIndex": true,
                  "defaultIndexProfileName": "fail_profile",
                  "primaryKey": {
                    "name": "id",
                    "type": "bigint"
                  },
                  "scalarColumns": [
                    {
                      "name": "title",
                      "type": "varchar",
                      "length": 200,
                      "nullable": true
                    }
                  ],
                  "vectorColumn": {
                    "name": "embedding",
                    "dimension": 768,
                    "elementType": "float32",
                    "metricType": "cosine",
                    "syncMode": "full_and_incremental"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-schemas/tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("failed to provision vector engine resources")));

        String collectionServingState = jdbcTemplate.queryForObject(
                "SELECT SERVING_STATE FROM SYS_VECTOR_COLLECTIONS_ WHERE COLLECTION_NAME = ?",
                String.class,
                "DOC_FAIL_EMBEDDING_V1"
        );
        String collectionStatus = jdbcTemplate.queryForObject(
                "SELECT COLLECTION_STATUS FROM SYS_VECTOR_COLLECTIONS_ WHERE COLLECTION_NAME = ?",
                String.class,
                "DOC_FAIL_EMBEDDING_V1"
        );
        String indexServingState = jdbcTemplate.queryForObject(
                "SELECT SERVING_STATE FROM SYS_VECTOR_INDEXES_ WHERE PROFILE_NAME = ?",
                String.class,
                "fail_profile"
        );
        String indexStatus = jdbcTemplate.queryForObject(
                "SELECT INDEX_STATUS FROM SYS_VECTOR_INDEXES_ WHERE PROFILE_NAME = ?",
                String.class,
                "fail_profile"
        );

        assertThat(collectionServingState).isEqualTo("BUILDING");
        assertThat(collectionStatus).isEqualTo("FAILED");
        assertThat(indexServingState).isEqualTo("OFFLINE");
        assertThat(indexStatus).isEqualTo("FAILED");
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
    static class ProvisioningTestConfiguration {

        @Bean
        TestVectorEngineAdminPort testVectorEngineAdminPort() {
            return new TestVectorEngineAdminPort();
        }

        @Bean
        @Primary
        VectorEngineAdminRouter vectorEngineAdminRouter(TestVectorEngineAdminPort testVectorEngineAdminPort) {
            return new VectorEngineAdminRouter(List.of(testVectorEngineAdminPort));
        }
    }

    static class TestVectorEngineAdminPort implements VectorEngineAdminPort {

        private volatile Mode mode = Mode.SUCCESS;

        void setMode(Mode mode) {
            this.mode = mode;
        }

        @Override
        public String engineType() {
            return "QDRANT";
        }

        @Override
        public EnsureResult ensureCollection(EnsureCollectionCommand command) {
            if (mode == Mode.FAIL_COLLECTION) {
                throw new RuntimeException("mock qdrant failure");
            }
            return EnsureResult.created("collection created");
        }

        @Override
        public EnsureResult ensureAlias(EnsureAliasCommand command) {
            return EnsureResult.created("alias created");
        }

        @Override
        public EnsureResult ensureIndex(EnsureIndexCommand command) {
            return EnsureResult.skippedNoop("index skipped");
        }

        enum Mode {
            SUCCESS,
            FAIL_COLLECTION
        }
    }
}
