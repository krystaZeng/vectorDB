package com.krystal.vectorsidecarservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(statements = "DELETE FROM SYS_VECTOR_COLUMNS_", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VectorRegistryControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRegisterAndListVectorColumn() throws Exception {
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
                .andExpect(jsonPath("$.data.tableName").value("doc"))
                .andExpect(jsonPath("$.data.metricType").value("COSINE"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.syncMode").value("FULL_AND_INCREMENTAL"));

        mockMvc.perform(get("/api/v1/vector-columns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].tableName").value("doc"))
                .andExpect(jsonPath("$.data[0].vectorColumn").value("embedding"));
    }

    @Test
    void shouldRejectDuplicateVectorColumnRegistration() throws Exception {
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
}
