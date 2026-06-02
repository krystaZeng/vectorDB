package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.data.VectorOutboxWorker;
import com.krystal.vectorsidecarservice.application.port.out.VectorOutboxEventPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorEngineDataPort;
import com.krystal.vectorsidecarservice.application.system.VectorEngineDataRouter;
import com.krystal.vectorsidecarservice.domain.data.VectorOutboxEventMeta;
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

import java.time.Instant;
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
        "DELETE FROM SYS_VECTOR_SOURCE_VERSIONS_",
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
    private VectorOutboxEventPort vectorOutboxEventPort;

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
        Map<String, Object> doneRow = jdbcTemplate.queryForMap(
                "SELECT EVENT_STATUS, ACTIVE_KEY FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                eventId
        );
        assertThat(doneRow.get("EVENT_STATUS")).isEqualTo("DONE");
        assertThat(doneRow.get("ACTIVE_KEY")).isNull();

        VectorEngineDataPort.UpsertPointCommand command = testVectorEngineDataPort.lastCommand();
        assertThat(command).isNotNull();
        assertThat(command.collectionName()).isEqualTo("DOC_OUTBOX_EMBEDDING_ACTIVE");
        assertThat(command.vectorName()).isEqualTo("default");
        assertThat(command.pointId()).isEqualTo(200L);
        assertThat(command.vector()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(command.payload()).containsEntry("docType", "news");
        assertThat(command.payload()).containsEntry("_sidecar_source_version", 1L);
    }

    @Test
    void shouldDrainUpdateOutboxEventAndUpsertLatestQdrantPoint() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long insertEventId = insertVectorRow();

        assertThat(vectorOutboxWorker.drainOnce(10)).isEqualTo(1);
        assertThat(vectorOutboxEventPort.findById(insertEventId).orElseThrow().eventStatus()).isEqualTo("DONE");
        testVectorEngineDataPort.reset();

        long updateEventId = updateVectorRow();

        assertThat(updateEventId).isNotEqualTo(insertEventId);
        assertThat(vectorOutboxWorker.drainOnce(10)).isEqualTo(1);

        Map<String, Object> doneRow = jdbcTemplate.queryForMap(
                """
                        SELECT EVENT_STATUS, EVENT_TYPE, SOURCE_OP, SOURCE_VERSION, ACTIVE_KEY
                        FROM SYS_VECTOR_OUTBOX_EVENTS_
                        WHERE EVENT_ID = ?
                        """,
                updateEventId
        );
        assertThat(doneRow.get("EVENT_STATUS")).isEqualTo("DONE");
        assertThat(doneRow.get("EVENT_TYPE")).isEqualTo("UPSERT");
        assertThat(doneRow.get("SOURCE_OP")).isEqualTo("UPDATE");
        assertThat(((Number) doneRow.get("SOURCE_VERSION")).longValue()).isEqualTo(2L);
        assertThat(doneRow.get("ACTIVE_KEY")).isNull();

        Map<String, Object> sourceRow = jdbcTemplate.queryForMap(
                "SELECT DOC_TYPE, ROW_VERSION FROM PUBLIC.DOC_OUTBOX WHERE ID = 200"
        );
        assertThat(sourceRow.get("DOC_TYPE")).isEqualTo("feature");
        assertThat(((Number) sourceRow.get("ROW_VERSION")).longValue()).isEqualTo(2L);

        VectorEngineDataPort.UpsertPointCommand command = testVectorEngineDataPort.lastCommand();
        assertThat(command).isNotNull();
        assertThat(command.collectionName()).isEqualTo("DOC_OUTBOX_EMBEDDING_ACTIVE");
        assertThat(command.vectorName()).isEqualTo("default");
        assertThat(command.pointId()).isEqualTo(200L);
        assertThat(command.vector()).containsExactly(0.7f, 0.8f, 0.9f);
        assertThat(command.payload()).containsEntry("docType", "feature");
        assertThat(command.payload()).containsEntry("_sidecar_source_version", 2L);
        assertThat(testVectorEngineDataPort.lastDeleteCommand()).isNull();
    }

    @Test
    void shouldDrainPendingDeleteOutboxEventAndDeleteQdrantPoint() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();
        deleteVectorRow();

        int processed = vectorOutboxWorker.drainOnce(10);

        assertThat(processed).isEqualTo(1);
        Map<String, Object> doneRow = jdbcTemplate.queryForMap(
                "SELECT EVENT_STATUS, EVENT_TYPE, SOURCE_OP, SOURCE_VERSION, ACTIVE_KEY FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                eventId
        );
        assertThat(doneRow.get("EVENT_STATUS")).isEqualTo("DONE");
        assertThat(doneRow.get("EVENT_TYPE")).isEqualTo("DELETE");
        assertThat(doneRow.get("SOURCE_OP")).isEqualTo("DELETE");
        assertThat(((Number) doneRow.get("SOURCE_VERSION")).longValue()).isEqualTo(2L);
        assertThat(doneRow.get("ACTIVE_KEY")).isNull();

        VectorEngineDataPort.DeletePointCommand command = testVectorEngineDataPort.lastDeleteCommand();
        assertThat(command).isNotNull();
        assertThat(command.collectionName()).isEqualTo("DOC_OUTBOX_EMBEDDING_ACTIVE");
        assertThat(command.pointId()).isEqualTo(200L);
        assertThat(testVectorEngineDataPort.lastCommand()).isNull();
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

    @Test
    void shouldMarkDirtyUpsertOutboxDeadWhenSourceVectorIsMissing() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        insertScalarOnlyRow(201);
        long eventId = enqueueDirtyUpsertEvent(columnId, 201);

        int processed = vectorOutboxWorker.drainOnce(10);

        assertThat(processed).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT EVENT_STATUS, ERROR_CODE, ERROR_MESSAGE, ACTIVE_KEY FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                eventId
        );
        assertThat(row.get("EVENT_STATUS")).isEqualTo("DEAD");
        assertThat(row.get("ERROR_CODE")).isEqualTo("VECTOR_MISSING");
        assertThat(row.get("ERROR_MESSAGE")).asString().contains("source row vector is empty: 201");
        assertThat(row.get("ACTIVE_KEY")).isEqualTo("TENANT_OUTBOX:" + columnId + ":201");
        assertThat(testVectorEngineDataPort.lastCommand()).isNull();
        assertThat(testVectorEngineDataPort.lastDeleteCommand()).isNull();
    }

    @Test
    void shouldRejectStaleClaimWhenPreviousWorkerMarksDoneAfterLockExpired() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();

        VectorOutboxEventMeta firstClaim = vectorOutboxEventPort.claim(
                eventId,
                "worker-a",
                "claim-a",
                Instant.now()
        ).orElseThrow();
        assertThat(firstClaim.claimToken()).isEqualTo("claim-a");

        int released = vectorOutboxEventPort.releaseExpiredProcessing(
                Instant.now().plusSeconds(60),
                Instant.now().minusMillis(1),
                Instant.now()
        );
        assertThat(released).isEqualTo(1);

        VectorOutboxEventMeta secondClaim = vectorOutboxEventPort.claim(
                eventId,
                "worker-b",
                "claim-b",
                Instant.now()
        ).orElseThrow();
        assertThat(secondClaim.claimToken()).isEqualTo("claim-b");

        assertThat(vectorOutboxEventPort.markDone(eventId, firstClaim.claimToken(), firstClaim.sourceVersion(), Instant.now()))
                .isEqualTo(VectorOutboxEventPort.OwnershipUpdateStatus.STALE_CLAIM);

        Map<String, Object> processingRow = jdbcTemplate.queryForMap(
                "SELECT EVENT_STATUS, CLAIM_TOKEN FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                eventId
        );
        assertThat(processingRow.get("EVENT_STATUS")).isEqualTo("PROCESSING");
        assertThat(processingRow.get("CLAIM_TOKEN")).isEqualTo("claim-b");

        assertThat(vectorOutboxEventPort.markDone(eventId, secondClaim.claimToken(), secondClaim.sourceVersion(), Instant.now()))
                .isEqualTo(VectorOutboxEventPort.OwnershipUpdateStatus.UPDATED);
    }

    @Test
    void shouldMergeDuplicateOutboxEventForSameActiveKey() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();

        VectorOutboxEventMeta existing = vectorOutboxEventPort.findById(eventId).orElseThrow();
        Long duplicateEventId = jdbcTemplate.queryForObject("SELECT NEXT VALUE FOR SYS_VECTOR_ID_SEQ", Long.class);
        VectorOutboxEventMeta duplicate = new VectorOutboxEventMeta(
                duplicateEventId,
                existing.tenantId(),
                existing.columnId(),
                existing.eventKey(),
                existing.activeKey(),
                existing.eventType(),
                existing.sourceOp(),
                existing.eventStatus(),
                existing.sourcePk(),
                existing.pointId(),
                existing.pkValueType(),
                existing.eventKey() + ":" + duplicateEventId,
                existing.sourceVersion() + 1,
                "N",
                0,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );

        VectorOutboxEventPort.SaveResult result = vectorOutboxEventPort.enqueueOrMergeActive(duplicate);

        assertThat(result.created()).isFalse();
        assertThat(result.event().eventId()).isEqualTo(eventId);
        assertThat(result.event().sourceVersion()).isEqualTo(existing.sourceVersion() + 1);
        assertThat(result.event().needsResync()).isEqualTo("N");
    }

    @Test
    void shouldMarkProcessingEventNeedsResyncForSameActiveKey() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();

        VectorOutboxEventMeta claim = vectorOutboxEventPort.claim(
                eventId,
                "worker-processing",
                "claim-processing",
                Instant.now()
        ).orElseThrow();
        Long duplicateEventId = jdbcTemplate.queryForObject("SELECT NEXT VALUE FOR SYS_VECTOR_ID_SEQ", Long.class);
        VectorOutboxEventMeta duplicate = new VectorOutboxEventMeta(
                duplicateEventId,
                claim.tenantId(),
                claim.columnId(),
                claim.eventKey(),
                claim.activeKey(),
                claim.eventType(),
                claim.sourceOp(),
                "PENDING",
                claim.sourcePk(),
                claim.pointId(),
                claim.pkValueType(),
                claim.eventKey() + ":" + duplicateEventId,
                claim.sourceVersion() + 1,
                "N",
                0,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );

        VectorOutboxEventPort.SaveResult result = vectorOutboxEventPort.enqueueOrMergeActive(duplicate);

        assertThat(result.created()).isFalse();
        assertThat(result.event().eventId()).isEqualTo(eventId);
        assertThat(result.event().eventStatus()).isEqualTo("PROCESSING");
        assertThat(result.event().sourceVersion()).isEqualTo(claim.sourceVersion() + 1);
        assertThat(result.event().needsResync()).isEqualTo("Y");
    }

    @Test
    void shouldMarkProcessingUpsertForDeleteResyncWithoutClearingClaim() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();

        VectorOutboxEventMeta claim = vectorOutboxEventPort.claim(
                eventId,
                "worker-delete-resync",
                "claim-delete-resync",
                Instant.now()
        ).orElseThrow();

        deleteVectorRow();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                        SELECT EVENT_STATUS, EVENT_TYPE, SOURCE_OP, SOURCE_VERSION, NEEDS_RESYNC, CLAIM_TOKEN
                        FROM SYS_VECTOR_OUTBOX_EVENTS_
                        WHERE EVENT_ID = ?
                        """,
                eventId
        );
        assertThat(row.get("EVENT_STATUS")).isEqualTo("PROCESSING");
        assertThat(row.get("EVENT_TYPE")).isEqualTo("DELETE");
        assertThat(row.get("SOURCE_OP")).isEqualTo("DELETE");
        assertThat(((Number) row.get("SOURCE_VERSION")).longValue()).isEqualTo(claim.sourceVersion() + 1);
        assertThat(row.get("NEEDS_RESYNC")).isEqualTo("Y");
        assertThat(row.get("CLAIM_TOKEN")).isEqualTo("claim-delete-resync");

        Integer sourceRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PUBLIC.DOC_OUTBOX WHERE ID = 200",
                Integer.class
        );
        assertThat(sourceRows).isZero();
    }

    @Test
    void shouldReturnProcessingEventToPendingWhenMarkDoneFindsResyncRequired() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();

        VectorOutboxEventMeta claim = vectorOutboxEventPort.claim(
                eventId,
                "worker-resync",
                "claim-resync",
                Instant.now()
        ).orElseThrow();
        Long duplicateEventId = jdbcTemplate.queryForObject("SELECT NEXT VALUE FOR SYS_VECTOR_ID_SEQ", Long.class);
        VectorOutboxEventMeta duplicate = new VectorOutboxEventMeta(
                duplicateEventId,
                claim.tenantId(),
                claim.columnId(),
                claim.eventKey(),
                claim.activeKey(),
                claim.eventType(),
                claim.sourceOp(),
                "PENDING",
                claim.sourcePk(),
                claim.pointId(),
                claim.pkValueType(),
                claim.eventKey() + ":" + duplicateEventId,
                claim.sourceVersion() + 1,
                "N",
                0,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now()
        );
        vectorOutboxEventPort.enqueueOrMergeActive(duplicate);

        assertThat(vectorOutboxEventPort.markDone(eventId, claim.claimToken(), claim.sourceVersion(), Instant.now()))
                .isEqualTo(VectorOutboxEventPort.OwnershipUpdateStatus.RESYNC_REQUIRED);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT EVENT_STATUS, NEEDS_RESYNC, ACTIVE_KEY, CLAIM_TOKEN FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                eventId
        );
        assertThat(row.get("EVENT_STATUS")).isEqualTo("PENDING");
        assertThat(row.get("NEEDS_RESYNC")).isEqualTo("N");
        assertThat(row.get("ACTIVE_KEY")).isNotNull();
        assertThat(row.get("CLAIM_TOKEN")).isNull();
    }

    @Test
    void shouldReturnProcessingEventToPendingWhenMarkRetryFindsResyncRequired() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();

        VectorOutboxEventMeta claim = vectorOutboxEventPort.claim(
                eventId,
                "worker-retry-resync",
                "claim-retry-resync",
                Instant.now()
        ).orElseThrow();
        vectorOutboxEventPort.enqueueOrMergeActive(duplicatePendingEvent(claim, claim.sourceVersion() + 1));

        assertThat(vectorOutboxEventPort.markRetry(
                eventId,
                claim.claimToken(),
                claim.sourceVersion(),
                1,
                Instant.now(),
                "MOCK_RETRY",
                "mock retry failure",
                Instant.now()
        )).isEqualTo(VectorOutboxEventPort.OwnershipUpdateStatus.RESYNC_REQUIRED);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT EVENT_STATUS, NEEDS_RESYNC, RETRY_COUNT, ERROR_CODE, CLAIM_TOKEN FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                eventId
        );
        assertThat(row.get("EVENT_STATUS")).isEqualTo("PENDING");
        assertThat(row.get("NEEDS_RESYNC")).isEqualTo("N");
        assertThat(((Number) row.get("RETRY_COUNT")).intValue()).isZero();
        assertThat(row.get("ERROR_CODE")).isNull();
        assertThat(row.get("CLAIM_TOKEN")).isNull();
    }

    @Test
    void shouldReturnProcessingEventToPendingWhenMarkDeadFindsResyncRequired() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();

        VectorOutboxEventMeta claim = vectorOutboxEventPort.claim(
                eventId,
                "worker-dead-resync",
                "claim-dead-resync",
                Instant.now()
        ).orElseThrow();
        vectorOutboxEventPort.enqueueOrMergeActive(duplicatePendingEvent(claim, claim.sourceVersion() + 1));

        assertThat(vectorOutboxEventPort.markDead(
                eventId,
                claim.claimToken(),
                claim.sourceVersion(),
                5,
                "MOCK_DEAD",
                "mock dead failure",
                Instant.now()
        )).isEqualTo(VectorOutboxEventPort.OwnershipUpdateStatus.RESYNC_REQUIRED);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT EVENT_STATUS, NEEDS_RESYNC, ERROR_CODE, FINISHED_AT, CLAIM_TOKEN FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                eventId
        );
        assertThat(row.get("EVENT_STATUS")).isEqualTo("PENDING");
        assertThat(row.get("NEEDS_RESYNC")).isEqualTo("N");
        assertThat(row.get("ERROR_CODE")).isNull();
        assertThat(row.get("FINISHED_AT")).isNull();
        assertThat(row.get("CLAIM_TOKEN")).isNull();
    }

    @Test
    void shouldManuallyRetryDeadOutboxEvent() throws Exception {
        long columnId = createVectorTable();
        registerReadyCollection(columnId);
        registerPayloadField(columnId);
        long eventId = insertVectorRow();

        VectorOutboxEventMeta claim = vectorOutboxEventPort.claim(
                eventId,
                "worker-dead",
                "claim-dead",
                Instant.now()
        ).orElseThrow();
        assertThat(vectorOutboxEventPort.markDead(
                eventId,
                claim.claimToken(),
                claim.sourceVersion(),
                5,
                "MOCK_ERROR",
                "mock failure",
                Instant.now()
        )).isEqualTo(VectorOutboxEventPort.OwnershipUpdateStatus.UPDATED);

        mockMvc.perform(get("/api/v1/vector-outbox-events")
                        .param("status", "DEAD")
                        .param("columnId", String.valueOf(columnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventId").value(eventId))
                .andExpect(jsonPath("$.data[0].eventStatus").value("DEAD"));

        mockMvc.perform(post("/api/v1/vector-outbox-events/{eventId}/retry", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.eventId").value(eventId))
                .andExpect(jsonPath("$.data.eventStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.retryCount").value(0))
                .andExpect(jsonPath("$.data.errorCode").doesNotExist())
                .andExpect(jsonPath("$.data.errorMessage").doesNotExist());

        Map<String, Object> retriedRow = jdbcTemplate.queryForMap(
                "SELECT EVENT_STATUS, RETRY_COUNT, NEEDS_RESYNC, CLAIM_TOKEN, ACTIVE_KEY FROM SYS_VECTOR_OUTBOX_EVENTS_ WHERE EVENT_ID = ?",
                eventId
        );
        assertThat(retriedRow.get("EVENT_STATUS")).isEqualTo("PENDING");
        assertThat(((Number) retriedRow.get("RETRY_COUNT")).intValue()).isEqualTo(0);
        assertThat(retriedRow.get("NEEDS_RESYNC")).isEqualTo("N");
        assertThat(retriedRow.get("CLAIM_TOKEN")).isNull();
        assertThat(retriedRow.get("ACTIVE_KEY")).isNotNull();
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
        Integer rowVersionColumnCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_SCHEMA = 'PUBLIC'
                          AND TABLE_NAME = 'DOC_OUTBOX'
                          AND COLUMN_NAME = 'ROW_VERSION'
                        """,
                Integer.class
        );
        assertThat(rowVersionColumnCount).isEqualTo(1);
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

    private long updateVectorRow() throws Exception {
        String updatePayload = """
                {
                  "tenantId": "tenant_outbox",
                  "schemaName": "public",
                  "tableName": "doc_outbox",
                  "vectorColumn": "embedding",
                  "pk": 200,
                  "vector": [0.7, 0.8, 0.9],
                  "payload": {
                    "docType": "feature"
                  }
                }
                """;

        String responseBody = mockMvc.perform(post("/api/v1/vector-data/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sourceVersion").value(2))
                .andExpect(jsonPath("$.data.vectorSyncStatus").value("VECTOR_SYNC_ENQUEUED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return getDataLong(responseBody, "outboxEventId");
    }

    private void insertScalarOnlyRow(long pk) throws Exception {
        String insertPayload = """
                {
                  "tenantId": "tenant_outbox",
                  "schemaName": "public",
                  "tableName": "doc_outbox",
                  "pk": %d,
                  "payload": {
                    "docType": "draft"
                  }
                }
                """.formatted(pk);

        mockMvc.perform(post("/api/v1/vector-data/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(insertPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vectorInserted").value(false));
    }

    private long enqueueDirtyUpsertEvent(long columnId, long pk) {
        Long eventId = jdbcTemplate.queryForObject("SELECT NEXT VALUE FOR SYS_VECTOR_ID_SEQ", Long.class);
        Instant now = Instant.now();
        String sourcePk = String.valueOf(pk);
        String eventKey = "TENANT_OUTBOX:" + columnId + ":" + sourcePk;
        VectorOutboxEventMeta event = new VectorOutboxEventMeta(
                eventId,
                "TENANT_OUTBOX",
                columnId,
                eventKey,
                eventKey,
                "UPSERT",
                "UPDATE",
                "PENDING",
                sourcePk,
                sourcePk,
                "NUMBER",
                eventKey + ":" + eventId,
                1,
                "N",
                0,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
        return vectorOutboxEventPort.enqueueOrMergeActive(event).event().eventId();
    }

    private void deleteVectorRow() throws Exception {
        String deletePayload = """
                {
                  "tenantId": "tenant_outbox",
                  "schemaName": "public",
                  "tableName": "doc_outbox",
                  "vectorColumn": "embedding",
                  "pk": 200
                }
                """;

        mockMvc.perform(post("/api/v1/vector-data/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deletePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vectorSyncStatus").value("PENDING_OUTBOX"));
    }

    private VectorOutboxEventMeta duplicatePendingEvent(VectorOutboxEventMeta base, long sourceVersion) {
        Long duplicateEventId = jdbcTemplate.queryForObject("SELECT NEXT VALUE FOR SYS_VECTOR_ID_SEQ", Long.class);
        Instant now = Instant.now();
        return new VectorOutboxEventMeta(
                duplicateEventId,
                base.tenantId(),
                base.columnId(),
                base.eventKey(),
                base.activeKey(),
                base.eventType(),
                base.sourceOp(),
                "PENDING",
                base.sourcePk(),
                base.pointId(),
                base.pkValueType(),
                base.eventKey() + ":" + duplicateEventId,
                sourceVersion,
                "N",
                0,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
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
        private volatile DeletePointCommand lastDeleteCommand;
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

        @Override
        public DeletePointResult deletePoint(DeletePointCommand command) {
            if (nextFailureMessage != null) {
                String message = nextFailureMessage;
                nextFailureMessage = null;
                throw new RuntimeException(message);
            }
            this.lastDeleteCommand = command;
            return DeletePointResult.deleted("point deleted");
        }

        @Override
        public SearchPointsResult searchPoints(SearchPointsCommand command) {
            return new SearchPointsResult(List.of());
        }

        void failNext(String message) {
            this.nextFailureMessage = message;
        }

        VectorEngineDataPort.UpsertPointCommand lastCommand() {
            return lastCommand;
        }

        VectorEngineDataPort.DeletePointCommand lastDeleteCommand() {
            return lastDeleteCommand;
        }

        void reset() {
            this.lastCommand = null;
            this.lastDeleteCommand = null;
            this.nextFailureMessage = null;
        }
    }
}
