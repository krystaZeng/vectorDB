package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "vector.outbox.worker.enabled=false",
        "vector.engine.qdrant.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(statements = {
        "DROP TABLE IF EXISTS PUBLIC.DOC_SELECT",
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
class VectorSelectControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestVectorEngineDataPort testVectorEngineDataPort;

    @Autowired
    private RegisterVectorColumnUseCase registerVectorColumnUseCase;

    @BeforeEach
    void resetVectorEngine() {
        testVectorEngineDataPort.reset();
    }

    @Test
    void shouldRunRelationalOnlySelect() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId, "CREATED");
        insertVectorRow(100, "news", List.of(0.1, 0.2, 0.3));
        insertVectorRow(101, "blog", List.of(0.4, 0.5, 0.6));

        String selectPayload = """
                {
                  "tenantId": "tenant_select",
                  "schemaName": "public",
                  "tableName": "doc_select",
                  "vectorColumn": "embedding",
                  "select": ["id", "doc_type"],
                  "where": [
                    { "field": "doc_type", "op": "EQ", "value": "news" }
                  ],
                  "limit": 10
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executionPlan").value("RELATIONAL_ONLY"))
                .andExpect(jsonPath("$.data.consistency").value("RELATIONAL_STRONG_READ"))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].pk").value(100))
                .andExpect(jsonPath("$.data.rows[0].score").doesNotExist())
                .andExpect(jsonPath("$.data.rows[0].values.ID").value(100))
                .andExpect(jsonPath("$.data.rows[0].values.DOC_TYPE").value("news"));
    }

    @Test
    void shouldHideVectorBytesFromDefaultRelationalProjection() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId, "CREATED");
        insertVectorRow(100, "news", List.of(0.1, 0.2, 0.3));

        String selectPayload = """
                {
                  "tenantId": "tenant_select",
                  "schemaName": "public",
                  "tableName": "doc_select",
                  "vectorColumn": "embedding",
                  "where": [
                    { "field": "doc_type", "op": "EQ", "value": "news" }
                  ],
                  "limit": 10
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executionPlan").value("RELATIONAL_ONLY"))
                .andExpect(jsonPath("$.data.rows[0].values.ID").value(100))
                .andExpect(jsonPath("$.data.rows[0].values.DOC_TYPE").value("news"))
                .andExpect(jsonPath("$.data.rows[0].values.EMBEDDING").doesNotExist());
    }

    @Test
    void shouldRunVectorFirstSelectAndDropStaleHits() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId, "CREATED");
        insertVectorRow(100, "news", List.of(0.1, 0.2, 0.3));
        insertVectorRow(101, "news", List.of(0.4, 0.5, 0.6));
        testVectorEngineDataPort.setHits(List.of(
                hit(100, 0.91, columnId, "100", "NUMBER", 1L),
                hit(999, 0.90, columnId, "999", "NUMBER", 1L),
                hit(101, 0.89, columnId, "101", "NUMBER", 0L)
        ));

        String selectPayload = """
                {
                  "tenantId": "tenant_select",
                  "schemaName": "public",
                  "tableName": "doc_select",
                  "vectorColumn": "embedding",
                  "select": ["id", "doc_type"],
                  "where": [
                    { "field": "doc_type", "op": "EQ", "value": "news" }
                  ],
                  "nearest": {
                    "vector": [0.1, 0.2, 0.3],
                    "topK": 2
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executionPlan").value("VECTOR_FIRST"))
                .andExpect(jsonPath("$.data.consistency").value("EVENTUAL_VECTOR_INDEX_STRICT"))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].pk").value(100))
                .andExpect(jsonPath("$.data.rows[0].score").value(0.91))
                .andExpect(jsonPath("$.data.rows[0].vectorIndexVersion").value(1))
                .andExpect(jsonPath("$.data.diagnostics.qdrantHitCount").value(3))
                .andExpect(jsonPath("$.data.diagnostics.staleDeletedHitCount").value(1))
                .andExpect(jsonPath("$.data.diagnostics.staleVersionHitCount").value(1))
                .andExpect(jsonPath("$.data.diagnostics.returnedRowCount").value(1));
    }

    @Test
    void shouldReturnVectorSearchRowsInHitScoreOrderWhenRelationalLookupOrderDiffers() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId, "CREATED");
        insertVectorRow(100, "news", List.of(0.1, 0.2, 0.3));
        insertVectorRow(101, "news", List.of(0.4, 0.5, 0.6));
        testVectorEngineDataPort.setHits(List.of(
                hit(101, 0.92, columnId, "101", "NUMBER", 1L),
                hit(100, 0.91, columnId, "100", "NUMBER", 1L)
        ));

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vectorSelectPayloadNoWhere()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executionPlan").value("VECTOR_FIRST"))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].pk").value(101))
                .andExpect(jsonPath("$.data.rows[0].score").value(0.92))
                .andExpect(jsonPath("$.data.rows[1].pk").value(100))
                .andExpect(jsonPath("$.data.rows[1].score").value(0.91));
    }

    @Test
    void shouldRejectVectorFilterWhenPayloadFieldIsNotRegistered() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);

        expectVectorFilterRejected(
                """
                { "field": "unknownPayload", "op": "EQ", "value": "news" }
                """,
                "FILTER_NOT_PUSHABLE"
        );
    }

    @Test
    void shouldRejectVectorFilterWhenPayloadIndexIsNotReady() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId, "MISSING");
        insertVectorRow(100, "news", List.of(0.1, 0.2, 0.3));

        String selectPayload = """
                {
                  "tenantId": "tenant_select",
                  "schemaName": "public",
                  "tableName": "doc_select",
                  "vectorColumn": "embedding",
                  "where": [
                    { "field": "doc_type", "op": "EQ", "value": "news" }
                  ],
                  "nearest": {
                    "vector": [0.1, 0.2, 0.3],
                    "topK": 2
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(selectPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("FILTER_NOT_PUSHABLE")));
    }

    @Test
    void shouldRejectSelectWhenMultipleVectorColumnsAndVectorColumnMissing() throws Exception {
        createVectorTable();
        registerSecondVectorColumnMetadata();

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "tenant_select",
                                  "schemaName": "public",
                                  "tableName": "doc_select",
                                  "nearest": {
                                    "vector": [0.1, 0.2, 0.3],
                                    "topK": 2
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("vectorColumn is required when table has multiple vector columns")));
    }

    @Test
    void shouldRejectNearestVectorDimensionMismatch() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "tenant_select",
                                  "schemaName": "public",
                                  "tableName": "doc_select",
                                  "vectorColumn": "embedding",
                                  "nearest": {
                                    "vector": [0.1, 0.2],
                                    "topK": 2
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("vector dimension mismatch")));
    }

    @Test
    void shouldRejectVectorSearchWhenCollectionIsNotReady() throws Exception {
        createVectorTable();

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vectorSelectPayloadNoWhere()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("ready vector collection not found")));
    }

    @Test
    void shouldAllowStringInVectorFilterAndPassNormalizedType() throws Exception {
        long columnId = createReadyTableWithPayloadField("DOC_TYPE", "docType", "KEYWORD");

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vectorSelectPayload("""
                                { "field": "docType", "op": "IN", "values": ["news", "blog"] }
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        VectorEngineDataPort.SearchFilterCondition filter = lastSearchFilter();
        assertThat(filter.fieldType()).isEqualTo("STRING");
        assertThat(filter.values()).containsExactly("news", "blog");
    }

    @Test
    void shouldRejectStringRangeVectorFilter() throws Exception {
        createReadyTableWithPayloadField("DOC_TYPE", "docType", "KEYWORD");

        expectVectorFilterRejected(
                """
                { "field": "docType", "op": "GT", "value": "news" }
                """,
                "FILTER_OP_NOT_SUPPORTED"
        );
    }

    @Test
    void shouldAllowIntegerRangeVectorFilterAndPassNormalizedType() throws Exception {
        createReadyTableWithPayloadField("AGE", "age", "INTEGER");

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vectorSelectPayload("""
                                { "field": "age", "op": "GT", "value": 18 }
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        VectorEngineDataPort.SearchFilterCondition filter = lastSearchFilter();
        assertThat(filter.fieldType()).isEqualTo("INTEGER");
        assertThat(filter.value()).isEqualTo(18);
    }

    @Test
    void shouldRejectIntegerDecimalVectorFilter() throws Exception {
        createReadyTableWithPayloadField("AGE", "age", "INTEGER");

        expectVectorFilterRejected(
                """
                { "field": "age", "op": "EQ", "value": 1.2 }
                """,
                "FILTER_VALUE_TYPE_MISMATCH"
        );
    }

    @Test
    void shouldRejectIntegerStringVectorFilter() throws Exception {
        createReadyTableWithPayloadField("AGE", "age", "INTEGER");

        expectVectorFilterRejected(
                """
                { "field": "age", "op": "EQ", "value": "1" }
                """,
                "FILTER_VALUE_TYPE_MISMATCH"
        );
    }

    @Test
    void shouldRejectIntegerDoubleLikeVectorFilter() throws Exception {
        createReadyTableWithPayloadField("AGE", "age", "INTEGER");

        expectVectorFilterRejected(
                """
                { "field": "age", "op": "EQ", "value": 1.0 }
                """,
                "FILTER_VALUE_TYPE_MISMATCH"
        );
    }

    @Test
    void shouldAllowDoubleRangeVectorFilterAndPassNormalizedType() throws Exception {
        createReadyTableWithPayloadField("PRICE", "price", "DOUBLE");

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vectorSelectPayload("""
                                { "field": "price", "op": "LTE", "value": 1.2 }
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        VectorEngineDataPort.SearchFilterCondition filter = lastSearchFilter();
        assertThat(filter.fieldType()).isEqualTo("DOUBLE");
        assertThat(filter.value()).isEqualTo(1.2);
    }

    @Test
    void shouldRejectDoubleStringVectorFilter() throws Exception {
        createReadyTableWithPayloadField("PRICE", "price", "DOUBLE");

        expectVectorFilterRejected(
                """
                { "field": "price", "op": "LTE", "value": "1.2" }
                """,
                "FILTER_VALUE_TYPE_MISMATCH"
        );
    }

    @Test
    void shouldAllowBooleanEqVectorFilterAndPassNormalizedType() throws Exception {
        createReadyTableWithPayloadField("AGE", "active", "BOOLEAN");

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vectorSelectPayload("""
                                { "field": "active", "op": "EQ", "value": true }
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        VectorEngineDataPort.SearchFilterCondition filter = lastSearchFilter();
        assertThat(filter.fieldType()).isEqualTo("BOOLEAN");
        assertThat(filter.value()).isEqualTo(true);
    }

    @Test
    void shouldRejectBooleanInVectorFilter() throws Exception {
        createReadyTableWithPayloadField("AGE", "active", "BOOLEAN");

        expectVectorFilterRejected(
                """
                { "field": "active", "op": "IN", "values": [true] }
                """,
                "FILTER_OP_NOT_SUPPORTED"
        );
    }

    @Test
    void shouldRejectEmptyInVectorFilter() throws Exception {
        createReadyTableWithPayloadField("DOC_TYPE", "docType", "KEYWORD");

        expectVectorFilterRejected(
                """
                { "field": "docType", "op": "IN", "values": [] }
                """,
                "FILTER_VALUE_REQUIRED"
        );
    }

    @Test
    void shouldRejectTooManyInValuesVectorFilter() throws Exception {
        createReadyTableWithPayloadField("AGE", "age", "INTEGER");

        expectVectorFilterRejected(
                "{ \"field\": \"age\", \"op\": \"IN\", \"values\": [" + integerCsv(101) + "] }",
                "FILTER_VALUE_TYPE_MISMATCH"
        );
    }

    @Test
    void shouldRejectMixedInValuesVectorFilter() throws Exception {
        createReadyTableWithPayloadField("DOC_TYPE", "docType", "KEYWORD");

        expectVectorFilterRejected(
                """
                { "field": "docType", "op": "IN", "values": ["news", 1] }
                """,
                "FILTER_VALUE_TYPE_MISMATCH"
        );
    }

    @Test
    void shouldRejectEqWithBothValueAndValuesVectorFilter() throws Exception {
        createReadyTableWithPayloadField("AGE", "age", "INTEGER");

        expectVectorFilterRejected(
                """
                { "field": "age", "op": "EQ", "value": 18, "values": [18, 19] }
                """,
                "FILTER_VALUE_AMBIGUOUS"
        );
    }

    @Test
    void shouldRejectInWithBothValueAndValuesVectorFilter() throws Exception {
        createReadyTableWithPayloadField("AGE", "age", "INTEGER");

        expectVectorFilterRejected(
                """
                { "field": "age", "op": "IN", "value": 18, "values": [18, 19] }
                """,
                "FILTER_VALUE_AMBIGUOUS"
        );
    }

    @Test
    void shouldRejectUnsupportedDateVectorFilter() throws Exception {
        createReadyTableWithPayloadField("EVENT_DATE", "eventDate", "DATE");

        expectVectorFilterRejected(
                """
                { "field": "eventDate", "op": "EQ", "value": "2026-05-28" }
                """,
                "FILTER_TYPE_NOT_SUPPORTED"
        );
    }

    @Test
    void shouldRejectIsNullVectorFilter() throws Exception {
        createReadyTableWithPayloadField("DOC_TYPE", "docType", "KEYWORD");

        expectVectorFilterRejected(
                """
                { "field": "docType", "op": "IS_NULL" }
                """,
                "FILTER_OP_NOT_SUPPORTED"
        );
    }

    @Test
    void shouldNotApplyVectorFilterTypeValidationToRelationalOnlySelect() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId, "CREATED");
        registerPayloadField(columnId, "EVENT_DATE", "eventDate", "DATE", "CREATED");
        insertVectorRow(100, "news", List.of(0.1, 0.2, 0.3));

        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "tenant_select",
                                  "schemaName": "public",
                                  "tableName": "doc_select",
                                  "vectorColumn": "embedding",
                                  "select": ["id"],
                                  "where": [
                                    { "field": "eventDate", "op": "IS_NULL" }
                                  ],
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.executionPlan").value("RELATIONAL_ONLY"))
                .andExpect(jsonPath("$.data.rows.length()").value(1));

        assertThat(testVectorEngineDataPort.lastSearchCommand()).isNull();
    }

    private long createVectorTable() throws Exception {
        String payload = """
                {
                  "tenantId": "tenant_select",
                  "schemaName": "public",
                  "tableName": "doc_select",
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
                    },
                    {
                      "name": "age",
                      "type": "integer",
                      "nullable": true
                    },
                    {
                      "name": "price",
                      "type": "double",
                      "nullable": true
                    },
                    {
                      "name": "event_date",
                      "type": "date",
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
                  "namespaceName": "tenant_select",
                  "collectionName": "DOC_SELECT_EMBEDDING_V1",
                  "aliasName": "DOC_SELECT_EMBEDDING_ACTIVE",
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

    private void registerPayloadField(long columnId, String payloadIndexStatus) throws Exception {
        registerPayloadField(columnId, "DOC_TYPE", "docType", "KEYWORD", payloadIndexStatus);
    }

    private void registerPayloadField(
            long columnId,
            String sourceColumnName,
            String payloadKey,
            String fieldType,
            String payloadIndexStatus
    ) throws Exception {
        String payload = """
                {
                  "columnId": %d,
                  "sourceColumnName": "%s",
                  "payloadKey": "%s",
                  "fieldType": "%s",
                  "isFilterable": "Y",
                  "isIndexed": "Y",
                  "syncEnabled": "Y",
                  "fieldStatus": "ACTIVE",
                  "payloadIndexStatus": "%s"
                }
                """.formatted(columnId, sourceColumnName, payloadKey, fieldType, payloadIndexStatus);

        mockMvc.perform(post("/api/v1/vector-payload-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private long createReadyTableWithPayloadField(String sourceColumnName, String payloadKey, String fieldType) throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId, sourceColumnName, payloadKey, fieldType, "CREATED");
        return columnId;
    }

    private void expectVectorFilterRejected(String conditionJson, String messagePart) throws Exception {
        mockMvc.perform(post("/api/v1/vector-data/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vectorSelectPayload(conditionJson)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString(messagePart)));
    }

    private String vectorSelectPayload(String conditionJson) {
        return """
                {
                  "tenantId": "tenant_select",
                  "schemaName": "public",
                  "tableName": "doc_select",
                  "vectorColumn": "embedding",
                  "select": ["id"],
                  "where": [
                    %s
                  ],
                  "nearest": {
                    "vector": [0.1, 0.2, 0.3],
                    "topK": 2
                  }
                }
                """.formatted(conditionJson);
    }

    private String vectorSelectPayloadNoWhere() {
        return """
                {
                  "tenantId": "tenant_select",
                  "schemaName": "public",
                  "tableName": "doc_select",
                  "vectorColumn": "embedding",
                  "select": ["id", "doc_type"],
                  "nearest": {
                    "vector": [0.1, 0.2, 0.3],
                    "topK": 2
                  }
                }
                """;
    }

    private void registerSecondVectorColumnMetadata() {
        registerVectorColumnUseCase.register(
                new RegisterVectorColumnUseCase.RegisterVectorColumnCommand(
                        "tenant_select",
                        "public",
                        "doc_select",
                        "id",
                        "embedding_2",
                        3,
                        "cosine",
                        "FLOAT32_LE",
                        "full_and_incremental",
                        "ACTIVE",
                        null,
                        null,
                        false
                )
        );
    }

    private VectorEngineDataPort.SearchFilterCondition lastSearchFilter() {
        VectorEngineDataPort.SearchPointsCommand command = testVectorEngineDataPort.lastSearchCommand();
        assertThat(command).isNotNull();
        assertThat(command.filters()).hasSize(1);
        return command.filters().get(0);
    }

    private void insertVectorRow(long pk, String docType, List<Double> vector) throws Exception {
        String insertPayload = """
                {
                  "tenantId": "tenant_select",
                  "schemaName": "public",
                  "tableName": "doc_select",
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

    private VectorEngineDataPort.SearchPointHit hit(
            long pointId,
            double score,
            long columnId,
            String sourcePk,
            String pkValueType,
            long vectorIndexVersion
    ) {
        return new VectorEngineDataPort.SearchPointHit(
                pointId,
                score,
                Map.of(
                        "_sidecar_column_id", columnId,
                        "_sidecar_source_pk", sourcePk,
                        "_sidecar_pk_value_type", pkValueType,
                        "_sidecar_vector_index_version", vectorIndexVersion
                )
        );
    }

    private String vectorCsv(List<Double> vector) {
        return vector.stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private String integerCsv(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(i);
        }
        return builder.toString();
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
    static class SelectTestConfiguration {

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

        private final List<SearchPointHit> hits = new ArrayList<>();
        private SearchPointsCommand lastSearchCommand;

        @Override
        public String engineType() {
            return "QDRANT";
        }

        @Override
        public UpsertPointResult upsertPoint(UpsertPointCommand command) {
            return UpsertPointResult.upserted("point upserted");
        }

        @Override
        public DeletePointResult deletePoint(DeletePointCommand command) {
            return DeletePointResult.deleted("point deleted");
        }

        @Override
        public SearchPointsResult searchPoints(SearchPointsCommand command) {
            this.lastSearchCommand = command;
            return new SearchPointsResult(List.copyOf(hits));
        }

        SearchPointsCommand lastSearchCommand() {
            return lastSearchCommand;
        }

        void setHits(List<SearchPointHit> hits) {
            this.hits.clear();
            this.hits.addAll(hits);
        }

        void reset() {
            this.hits.clear();
            this.lastSearchCommand = null;
        }
    }
}
