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
        "DROP TABLE IF EXISTS PUBLIC.DOC_STORE",
        "DROP TABLE IF EXISTS PUBLIC.DOC_STORE_AUTO",
        "DELETE FROM SYS_VECTOR_INDEXES_",
        "DELETE FROM SYS_VECTOR_COLLECTIONS_",
        "DELETE FROM SYS_VECTOR_COLUMNS_"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VectorSchemaControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreateBusinessTableAndRegisterVectorColumn() throws Exception {
        String payload = """
                {
                  "tenantId": "tenant_a",
                  "schemaName": "public",
                  "tableName": "doc_store",
                  "ifNotExists": true,
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
                    },
                    {
                      "name": "category",
                      "type": "varchar",
                      "length": 50,
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.schemaName").value("PUBLIC"))
                .andExpect(jsonPath("$.data.tableName").value("DOC_STORE"))
                .andExpect(jsonPath("$.data.vectorColumn").value("EMBEDDING"))
                .andExpect(jsonPath("$.data.dimension").value(768))
                .andExpect(jsonPath("$.data.metricType").value("COSINE"))
                .andExpect(jsonPath("$.data.ddlExecuted").value(true));

        Integer tableCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM INFORMATION_SCHEMA.TABLES
                        WHERE TABLE_SCHEMA = 'PUBLIC'
                          AND TABLE_NAME = 'DOC_STORE'
                        """,
                Integer.class
        );
        assertThat(tableCount).isEqualTo(1);

        Integer vectorColumnCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_SCHEMA = 'PUBLIC'
                          AND TABLE_NAME = 'DOC_STORE'
                          AND COLUMN_NAME = 'EMBEDDING'
                        """,
                Integer.class
        );
        assertThat(vectorColumnCount).isEqualTo(1);

        mockMvc.perform(get("/api/v1/vector-columns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].tableName").value("DOC_STORE"))
                .andExpect(jsonPath("$.data[0].pkColumn").value("ID"))
                .andExpect(jsonPath("$.data[0].vectorColumn").value("EMBEDDING"))
                .andExpect(jsonPath("$.data[0].dimension").value(768))
                .andExpect(jsonPath("$.data[0].metricType").value("COSINE"))
                .andExpect(jsonPath("$.data[0].syncMode").value("FULL_AND_INCREMENTAL"));
    }

    @Test
    void shouldCreateTableAndAutoRegisterDefaultCollectionAndIndex() throws Exception {
        String payload = """
                {
                  "tenantId": "tenant_auto",
                  "schemaName": "public",
                  "tableName": "doc_store_auto",
                  "ifNotExists": true,
                  "autoRegisterCollection": true,
                  "autoRegisterIndex": true,
                  "defaultIndexProfileName": "default_profile",
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
                .andExpect(jsonPath("$.data.tableName").value("DOC_STORE_AUTO"))
                .andExpect(jsonPath("$.data.collectionId").isNumber())
                .andExpect(jsonPath("$.data.indexId").isNumber())
                .andExpect(jsonPath("$.data.indexProfileName").value("default_profile"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long columnId = getDataLong(responseBody, "columnId");
        long collectionId = getDataLong(responseBody, "collectionId");
        long indexId = getDataLong(responseBody, "indexId");

        mockMvc.perform(get("/api/v1/vector-collections").param("columnId", String.valueOf(columnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].collectionId").value(collectionId))
                .andExpect(jsonPath("$.data[0].collectionName").value("DOC_STORE_AUTO_EMBEDDING_V1"))
                .andExpect(jsonPath("$.data[0].distanceMetric").value("COSINE"))
                .andExpect(jsonPath("$.data[0].servingState").value("BUILDING"))
                .andExpect(jsonPath("$.data[0].collectionStatus").value("CREATING"));

        mockMvc.perform(get("/api/v1/vector-indexes").param("columnId", String.valueOf(columnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].indexId").value(indexId))
                .andExpect(jsonPath("$.data[0].profileName").value("default_profile"))
                .andExpect(jsonPath("$.data[0].isDefault").value("Y"))
                .andExpect(jsonPath("$.data[0].metricType").value("COSINE"))
                .andExpect(jsonPath("$.data[0].servingState").value("OFFLINE"))
                .andExpect(jsonPath("$.data[0].indexStatus").value("CREATING"));
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
