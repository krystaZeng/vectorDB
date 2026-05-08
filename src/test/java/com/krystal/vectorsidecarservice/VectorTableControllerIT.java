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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(statements = {
        "DROP TABLE IF EXISTS PUBLIC.DOC_SIMPLE",
        "DELETE FROM SYS_VECTOR_SYNC_ERRORS_",
        "DELETE FROM SYS_VECTOR_SYNC_PROGRESS_",
        "DELETE FROM SYS_VECTOR_SYNC_JOBS_",
        "DELETE FROM SYS_VECTOR_PAYLOAD_FIELDS_",
        "DELETE FROM SYS_VECTOR_OUTBOX_EVENTS_",
        "DELETE FROM SYS_VECTOR_INDEXES_",
        "DELETE FROM SYS_VECTOR_COLLECTIONS_",
        "DELETE FROM SYS_VECTOR_COLUMNS_"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VectorTableControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreateSimpleVectorTableRegisterPayloadFieldsAndSupportScalarOnlyInsert() throws Exception {
        String createPayload = """
                {
                  "tenantId": "tenant_simple",
                  "schemaName": "public",
                  "tableName": "doc_simple",
                  "primaryKey": {
                    "name": "id",
                    "type": "bigint"
                  },
                  "scalarColumns": [
                    {
                      "name": "doc_type",
                      "type": "varchar",
                      "length": 50,
                      "nullable": true,
                      "payloadKey": "docType"
                    },
                    {
                      "name": "score",
                      "type": "double",
                      "nullable": true,
                      "payloadKey": "score",
                      "payloadSyncEnabled": false
                    }
                  ],
                  "vectorColumn": {
                    "name": "embedding",
                    "dimension": 3
                  }
                }
                """;

        String responseBody = mockMvc.perform(post("/api/v1/vector-tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.schemaName").value("PUBLIC"))
                .andExpect(jsonPath("$.data.tableName").value("DOC_SIMPLE"))
                .andExpect(jsonPath("$.data.vectorColumn").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.dimension").value(3))
                .andExpect(jsonPath("$.data.metricType").value("COSINE"))
                .andExpect(jsonPath("$.data.collectionId").isNumber())
                .andExpect(jsonPath("$.data.indexId").isNumber())
                .andExpect(jsonPath("$.data.indexProfileName").value("default"))
                .andExpect(jsonPath("$.data.ddlExecuted").value(true))
                .andExpect(jsonPath("$.data.payloadFields.length()").value(2))
                .andExpect(jsonPath("$.data.payloadFields[0].sourceColumnName").value("doc_type"))
                .andExpect(jsonPath("$.data.payloadFields[0].payloadKey").value("docType"))
                .andExpect(jsonPath("$.data.payloadFields[0].fieldType").value("KEYWORD"))
                .andExpect(jsonPath("$.data.payloadFields[0].syncEnabled").value("Y"))
                .andExpect(jsonPath("$.data.payloadFields[0].fieldStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.payloadFields[1].sourceColumnName").value("score"))
                .andExpect(jsonPath("$.data.payloadFields[1].payloadKey").value("score"))
                .andExpect(jsonPath("$.data.payloadFields[1].fieldType").value("FLOAT"))
                .andExpect(jsonPath("$.data.payloadFields[1].syncEnabled").value("N"))
                .andExpect(jsonPath("$.data.payloadFields[1].fieldStatus").value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long columnId = getDataLong(responseBody, "columnId");

        mockMvc.perform(get("/api/v1/vector-payload-fields").param("columnId", String.valueOf(columnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(post("/api/v1/vector-tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.columnId").value(columnId))
                .andExpect(jsonPath("$.data.ddlExecuted").value(false))
                .andExpect(jsonPath("$.data.payloadFields.length()").value(2));

        String insertPayload = """
                {
                  "tenantId": "tenant_simple",
                  "schemaName": "public",
                  "tableName": "doc_simple",
                  "pk": 1,
                  "payload": {
                    "docType": "article",
                    "score": 9.5
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(insertPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.columnId").value(columnId))
                .andExpect(jsonPath("$.data.tableName").value("DOC_SIMPLE"))
                .andExpect(jsonPath("$.data.vectorColumn").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.relationalInserted").value(true))
                .andExpect(jsonPath("$.data.vectorInserted").value(false))
                .andExpect(jsonPath("$.data.vectorUpsertStatus").value("SKIPPED_SCALAR_ONLY"));

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PUBLIC.DOC_SIMPLE WHERE ID = 1 AND DOC_TYPE = 'article' AND SCORE = 9.5",
                Integer.class
        );
        assertThat(rowCount).isEqualTo(1);
        byte[] vectorBytes = jdbcTemplate.queryForObject(
                "SELECT EMBEDDING FROM PUBLIC.DOC_SIMPLE WHERE ID = 1",
                byte[].class
        );
        assertThat(vectorBytes).isNull();
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
