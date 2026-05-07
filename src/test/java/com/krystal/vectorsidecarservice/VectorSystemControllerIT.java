package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Sql(statements = {
        "DELETE FROM SYS_VECTOR_SYNC_ERRORS_",
        "DELETE FROM SYS_VECTOR_SYNC_PROGRESS_",
        "DELETE FROM SYS_VECTOR_SYNC_JOBS_",
        "DELETE FROM SYS_VECTOR_PAYLOAD_FIELDS_",
        "DELETE FROM SYS_VECTOR_INDEXES_",
        "DELETE FROM SYS_VECTOR_COLLECTIONS_",
        "DELETE FROM SYS_VECTOR_COLUMNS_"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VectorSystemControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisterVectorColumnUseCase registerVectorColumnUseCase;

    @Test
    void shouldManageSystemTablesViaApis() throws Exception {
        var column = registerVectorColumnUseCase.register(
                new RegisterVectorColumnUseCase.RegisterVectorColumnCommand(
                        "tenant_a",
                        "public",
                        "doc",
                        "id",
                        "embedding",
                        768,
                        "cosine",
                        "full_and_incremental"
                )
        );

        String collectionPayload = """
                {
                  "columnId": %d,
                  "namespaceName": "tenant_a",
                  "collectionName": "doc_embeddings_v1",
                  "aliasName": "doc_embeddings_active",
                  "collectionVersion": "v1",
                  "qdrantVectorName": "default",
                  "vectorDim": 768,
                  "distanceMetric": "cosine",
                  "qdrantIdType": "uint64",
                  "shardNumber": 2,
                  "replicationFactor": 1,
                  "writeConsistencyFactor": 1,
                  "onDiskPayload": "N"
                }
                """.formatted(column.columnId());

        String collectionBody = mockMvc.perform(post("/api/v1/vector-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(collectionPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.columnId").value(column.columnId()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long collectionId = getDataLong(collectionBody, "collectionId");

        String indexPayload = """
                {
                  "columnId": %d,
                  "collectionId": %d,
                  "profileName": "default_profile",
                  "indexType": "HNSW",
                  "metricType": "COSINE",
                  "indexParamsJson": "{\\"m\\":16}",
                  "searchParamsJson": "{\\"ef_search\\":64}",
                  "isDefault": "Y",
                  "servingState": "ONLINE",
                  "indexStatus": "READY",
                  "buildVersion": "v1"
                }
                """.formatted(column.columnId(), collectionId);

        String indexBody = mockMvc.perform(post("/api/v1/vector-indexes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(indexPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long indexId = getDataLong(indexBody, "indexId");

        String payloadFieldPayload = """
                {
                  "columnId": %d,
                  "sourceColumnName": "doc_type",
                  "payloadKey": "docType",
                  "fieldType": "KEYWORD",
                  "isFilterable": "Y",
                  "isReturnable": "Y",
                  "isIndexed": "Y",
                  "syncEnabled": "Y",
                  "fieldStatus": "ACTIVE"
                }
                """.formatted(column.columnId());

        mockMvc.perform(post("/api/v1/vector-payload-fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadFieldPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        String syncJobPayload = """
                {
                  "columnId": %d,
                  "collectionId": %d,
                  "indexId": %d,
                  "jobType": "BACKFILL",
                  "triggerType": "MANUAL",
                  "idempotencyKey": "job-doc-v1",
                  "snapshotId": "snapshot-1",
                  "workerId": "worker-1"
                }
                """.formatted(column.columnId(), collectionId, indexId);

        String jobBody = mockMvc.perform(post("/api/v1/vector-sync-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(syncJobPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long jobId = getDataLong(jobBody, "jobId");

        String progressPayload = """
                {
                  "jobId": %d,
                  "columnId": %d,
                  "partitionId": "p0",
                  "lastPk": "100",
                  "lastEventId": "evt-100",
                  "processedRows": 100,
                  "successRows": 99,
                  "failedRows": 1,
                  "progressStatus": "RUNNING",
                  "checkpointData": "{\\"batch\\":10}"
                }
                """.formatted(jobId, column.columnId());

        mockMvc.perform(post("/api/v1/vector-sync-progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progressPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        String errorPayload = """
                {
                  "jobId": %d,
                  "columnId": %d,
                  "partitionId": "p0",
                  "sourcePk": "100",
                  "opType": "UPSERT",
                  "errorStage": "UPSERT",
                  "errorCode": "QDRANT_TIMEOUT",
                  "errorMessage": "upsert timed out",
                  "payloadSnapshot": "{\\"docId\\":100}",
                  "dedupeKey": "job-doc-v1:p0:100:UPSERT:QDRANT_TIMEOUT",
                  "retryCount": 0,
                  "errorStatus": "OPEN"
                }
                """.formatted(jobId, column.columnId());

        mockMvc.perform(post("/api/v1/vector-sync-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(errorPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/vector-collections").param("columnId", String.valueOf(column.columnId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/vector-indexes").param("columnId", String.valueOf(column.columnId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/vector-payload-fields").param("columnId", String.valueOf(column.columnId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/vector-sync-jobs").param("columnId", String.valueOf(column.columnId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/vector-sync-progress").param("jobId", String.valueOf(jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/v1/vector-sync-errors").param("jobId", String.valueOf(jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    private long getDataLong(String body, String field) throws Exception {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Cannot find numeric field in response: " + field);
        }
        return Long.parseLong(matcher.group(1));
    }
}
