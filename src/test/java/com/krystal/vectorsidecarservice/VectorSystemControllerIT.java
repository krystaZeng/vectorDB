package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorCollectionUseCase;
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
        "DELETE FROM SYS_VECTOR_OUTBOX_EVENTS_",
        "DELETE FROM SYS_VECTOR_SOURCE_VERSIONS_",
        "DELETE FROM SYS_VECTOR_INDEXES_",
        "DELETE FROM SYS_VECTOR_COLLECTIONS_",
        "DELETE FROM SYS_VECTOR_COLUMNS_"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VectorSystemControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisterVectorColumnUseCase registerVectorColumnUseCase;

    @Autowired
    private RegisterVectorCollectionUseCase registerVectorCollectionUseCase;

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
                        "full_and_incremental",
                        null,
                        null,
                        null
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

    @Test
    void shouldRejectNegativeSyncProgressCountersViaApi() throws Exception {
        String payload = """
                {
                  "jobId": 1,
                  "columnId": 1,
                  "partitionId": "p0",
                  "processedRows": -1,
                  "successRows": 0,
                  "failedRows": 0,
                  "progressStatus": "RUNNING"
                }
                """;

        mockMvc.perform(post("/api/v1/vector-sync-progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("processedRows")));
    }

    @Test
    void shouldRejectNegativeSyncErrorRetryCountViaApi() throws Exception {
        String payload = """
                {
                  "jobId": 1,
                  "columnId": 1,
                  "sourcePk": "100",
                  "opType": "UPSERT",
                  "errorStage": "UPSERT",
                  "errorCode": "QDRANT_TIMEOUT",
                  "errorMessage": "upsert timed out",
                  "dedupeKey": "negative-retry-count",
                  "retryCount": -1,
                  "errorStatus": "OPEN"
                }
                """;

        mockMvc.perform(post("/api/v1/vector-sync-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("retryCount")));
    }

    private long getDataLong(String body, String field) throws Exception {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Cannot find numeric field in response: " + field);
        }
        return Long.parseLong(matcher.group(1));
    }

    @Test
    void shouldRejectInvalidCollectionLifecycleCombination() throws Exception {
        var column = registerVectorColumnUseCase.register(
                new RegisterVectorColumnUseCase.RegisterVectorColumnCommand(
                        "tenant_lifecycle",
                        "public",
                        "doc_lifecycle",
                        "id",
                        "embedding",
                        768,
                        "cosine",
                        "full_and_incremental",
                        null,
                        null,
                        null
                )
        );

        String payload = """
                {
                  "columnId": %d,
                  "namespaceName": "tenant_lifecycle",
                  "collectionName": "doc_lifecycle_embeddings_v1",
                  "collectionVersion": "v1",
                  "qdrantVectorName": "default",
                  "vectorDim": 768,
                  "distanceMetric": "cosine",
                  "qdrantIdType": "uint64",
                  "servingState": "ACTIVE",
                  "collectionStatus": "FAILED",
                  "onDiskPayload": "N"
                }
                """.formatted(column.columnId());

        mockMvc.perform(post("/api/v1/vector-collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "collection lifecycle has invalid state combination: servingState=ACTIVE, collectionStatus=FAILED"
                ));
    }

    @Test
    void shouldRejectInvalidIndexLifecycleCombination() throws Exception {
        var column = registerVectorColumnUseCase.register(
                new RegisterVectorColumnUseCase.RegisterVectorColumnCommand(
                        "tenant_index_lifecycle",
                        "public",
                        "doc_index_lifecycle",
                        "id",
                        "embedding",
                        768,
                        "cosine",
                        "full_and_incremental",
                        null,
                        null,
                        null
                )
        );
        var collection = registerVectorCollectionUseCase.register(
                new RegisterVectorCollectionUseCase.RegisterVectorCollectionCommand(
                        column.columnId(),
                        "QDRANT",
                        "tenant_index_lifecycle",
                        "doc_index_lifecycle_embeddings_v1",
                        "doc_index_lifecycle_embeddings_active",
                        "v1",
                        "default",
                        768,
                        "cosine",
                        "uint64",
                        "ACTIVE",
                        "READY",
                        null,
                        null,
                        null,
                        "N",
                        null,
                        null,
                        null
                )
        );

        String payload = """
                {
                  "columnId": %d,
                  "collectionId": %d,
                  "profileName": "invalid_lifecycle_profile",
                  "indexType": "HNSW",
                  "metricType": "COSINE",
                  "isDefault": "Y",
                  "servingState": "ONLINE",
                  "indexStatus": "CREATING",
                  "buildVersion": "v1"
                }
                """.formatted(column.columnId(), collection.collectionId());

        mockMvc.perform(post("/api/v1/vector-indexes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "index lifecycle has invalid state combination: servingState=ONLINE, indexStatus=CREATING"
                ));
    }
}
