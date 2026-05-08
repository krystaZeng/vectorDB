package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.port.in.CreateVectorSyncJobUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RecordVectorSyncErrorUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorCollectionUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorIndexUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorPayloadFieldUseCase;
import com.krystal.vectorsidecarservice.application.port.in.UpsertVectorSyncProgressUseCase;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;
import com.krystal.vectorsidecarservice.domain.sync.VectorSyncJobMeta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("local")
@Sql(statements = {
        "DELETE FROM SYS_VECTOR_SYNC_ERRORS_",
        "DELETE FROM SYS_VECTOR_SYNC_PROGRESS_",
        "DELETE FROM SYS_VECTOR_SYNC_JOBS_",
        "DELETE FROM SYS_VECTOR_PAYLOAD_FIELDS_",
        "DELETE FROM SYS_VECTOR_OUTBOX_EVENTS_",
        "DELETE FROM SYS_VECTOR_INDEXES_",
        "DELETE FROM SYS_VECTOR_COLLECTIONS_",
        "DELETE FROM SYS_VECTOR_COLUMNS_"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VectorSystemServiceIT {

    @Autowired
    private RegisterVectorColumnUseCase registerVectorColumnUseCase;

    @Autowired
    private RegisterVectorCollectionUseCase registerVectorCollectionUseCase;

    @Autowired
    private RegisterVectorIndexUseCase registerVectorIndexUseCase;

    @Autowired
    private RegisterVectorPayloadFieldUseCase registerVectorPayloadFieldUseCase;

    @Autowired
    private CreateVectorSyncJobUseCase createVectorSyncJobUseCase;

    @Autowired
    private UpsertVectorSyncProgressUseCase upsertVectorSyncProgressUseCase;

    @Autowired
    private RecordVectorSyncErrorUseCase recordVectorSyncErrorUseCase;

    @Test
    void shouldRegisterAndTrackSystemTables() {
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

        var collection = registerVectorCollectionUseCase.register(
                new RegisterVectorCollectionUseCase.RegisterVectorCollectionCommand(
                        column.columnId(),
                        "QDRANT",
                        "tenant_a",
                        "doc_embeddings_v1",
                        "doc_embeddings_active",
                        "v1",
                        "default",
                        768,
                        "cosine",
                        "uint64",
                        "ACTIVE",
                        "READY",
                        2,
                        1,
                        1,
                        "N",
                        "{\"m\":16,\"ef_construct\":200}",
                        null,
                        null
                )
        );

        var index = registerVectorIndexUseCase.register(
                new RegisterVectorIndexUseCase.RegisterVectorIndexCommand(
                        column.columnId(),
                        collection.collectionId(),
                        "default_profile",
                        "HNSW",
                        "COSINE",
                        "{\"m\":16}",
                        "{\"ef_search\":64}",
                        null,
                        "Y",
                        "ONLINE",
                        "READY",
                        "v1"
                )
        );

        var payloadField = registerVectorPayloadFieldUseCase.register(
                new RegisterVectorPayloadFieldUseCase.RegisterVectorPayloadFieldCommand(
                        column.columnId(),
                        "doc_type",
                        "docType",
                        "KEYWORD",
                        "Y",
                        "Y",
                        "Y",
                        "Y",
                        "ACTIVE",
                        null
                )
        );

        var syncJob = createVectorSyncJobUseCase.create(
                new CreateVectorSyncJobUseCase.CreateVectorSyncJobCommand(
                        column.columnId(),
                        collection.collectionId(),
                        index.indexId(),
                        "BACKFILL",
                        "MANUAL",
                        "job-doc-v1",
                        "snapshot-1",
                        null,
                        null,
                        null,
                        "worker-1"
                )
        );

        var progress = upsertVectorSyncProgressUseCase.upsert(
                new UpsertVectorSyncProgressUseCase.UpsertVectorSyncProgressCommand(
                        null,
                        syncJob.jobId(),
                        column.columnId(),
                        "p0",
                        "100",
                        "evt-100",
                        100L,
                        99L,
                        1L,
                        "RUNNING",
                        "{\"batch\":10}"
                )
        );

        var error = recordVectorSyncErrorUseCase.record(
                new RecordVectorSyncErrorUseCase.RecordVectorSyncErrorCommand(
                        syncJob.jobId(),
                        column.columnId(),
                        "p0",
                        "100",
                        "UPSERT",
                        "UPSERT",
                        "QDRANT_TIMEOUT",
                        "upsert timed out",
                        "{\"docId\":100}",
                        "job-doc-v1:p0:100:UPSERT:QDRANT_TIMEOUT",
                        0,
                        null,
                        "OPEN"
                )
        );

        assertNotNull(collection.collectionId());
        assertNotNull(index.indexId());
        assertNotNull(payloadField.fieldId());
        assertNotNull(syncJob.jobId());
        assertNotNull(progress.progressId());
        assertNotNull(error.errorId());

        assertEquals(1, registerVectorCollectionUseCase.listByColumnId(column.columnId()).size());
        assertEquals(1, registerVectorIndexUseCase.listByColumnId(column.columnId()).size());
        assertEquals(1, registerVectorPayloadFieldUseCase.listByColumnId(column.columnId()).size());
        assertEquals(1, createVectorSyncJobUseCase.listByColumnId(column.columnId()).size());
        assertEquals(1, upsertVectorSyncProgressUseCase.listByJobId(syncJob.jobId()).size());
        assertEquals(1, recordVectorSyncErrorUseCase.listByJobId(syncJob.jobId()).size());
    }

    @Test
    void shouldRejectNegativeSyncProgressCountersAtServiceLayer() {
        assertThatThrownBy(() -> upsertVectorSyncProgressUseCase.upsert(
                new UpsertVectorSyncProgressUseCase.UpsertVectorSyncProgressCommand(
                        null,
                        1L,
                        1L,
                        "p0",
                        null,
                        null,
                        -1L,
                        0L,
                        0L,
                        "RUNNING",
                        null
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("processedRows must be >= 0");
    }

    @Test
    void shouldRejectNegativeSyncErrorRetryCountAtServiceLayer() {
        assertThatThrownBy(() -> recordVectorSyncErrorUseCase.record(
                new RecordVectorSyncErrorUseCase.RecordVectorSyncErrorCommand(
                        1L,
                        1L,
                        "p0",
                        "100",
                        "UPSERT",
                        "UPSERT",
                        "QDRANT_TIMEOUT",
                        "upsert timed out",
                        null,
                        "negative-retry-count",
                        -1,
                        null,
                        "OPEN"
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("retryCount must be >= 0");
    }

    @Test
    void shouldRejectSyncJobWhenColumnIsNotActive() {
        var column = registerVectorColumnUseCase.register(
                new RegisterVectorColumnUseCase.RegisterVectorColumnCommand(
                        "tenant_building",
                        "public",
                        "doc_building",
                        "id",
                        "embedding",
                        768,
                        "cosine",
                        "full_and_incremental",
                        "BUILDING",
                        null,
                        null
                )
        );

        assertThatThrownBy(() -> createVectorSyncJobUseCase.create(
                new CreateVectorSyncJobUseCase.CreateVectorSyncJobCommand(
                        column.columnId(),
                        null,
                        null,
                        "BACKFILL",
                        "MANUAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("vector column is not ACTIVE: " + column.columnId());
    }

    @Test
    void shouldRejectSyncJobWhenCollectionIsNotReady() {
        var column = registerVectorColumnUseCase.register(
                new RegisterVectorColumnUseCase.RegisterVectorColumnCommand(
                        "tenant_collection_building",
                        "public",
                        "doc_collection_building",
                        "id",
                        "embedding",
                        768,
                        "cosine",
                        "full_and_incremental",
                        "ACTIVE",
                        null,
                        null
                )
        );
        var collection = registerVectorCollectionUseCase.register(
                new RegisterVectorCollectionUseCase.RegisterVectorCollectionCommand(
                        column.columnId(),
                        "QDRANT",
                        "tenant_collection_building",
                        "doc_collection_building_embeddings_v1",
                        null,
                        "v1",
                        "default",
                        768,
                        "cosine",
                        "uint64",
                        "BUILDING",
                        "CREATING",
                        null,
                        null,
                        null,
                        "N",
                        null,
                        null,
                        null
                )
        );

        assertThatThrownBy(() -> createVectorSyncJobUseCase.create(
                new CreateVectorSyncJobUseCase.CreateVectorSyncJobCommand(
                        column.columnId(),
                        collection.collectionId(),
                        null,
                        "BACKFILL",
                        "MANUAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("vector collection is not READY: " + collection.collectionId());
    }

    @Test
    void shouldRejectCollectionWhenColumnDoesNotExist() {
        assertThatThrownBy(() -> registerVectorCollectionUseCase.register(
                new RegisterVectorCollectionUseCase.RegisterVectorCollectionCommand(
                        999L,
                        "QDRANT",
                        "tenant_missing_column",
                        "missing_column_embeddings_v1",
                        null,
                        "v1",
                        "default",
                        768,
                        "cosine",
                        "uint64",
                        "BUILDING",
                        "CREATING",
                        null,
                        null,
                        null,
                        "N",
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("vector column not found: 999");
    }

    @Test
    void shouldRejectCollectionWhenDefinitionDoesNotMatchColumn() {
        var column = registerActiveColumn("collection_definition_mismatch");

        assertThatThrownBy(() -> registerVectorCollectionUseCase.register(
                new RegisterVectorCollectionUseCase.RegisterVectorCollectionCommand(
                        column.columnId(),
                        "QDRANT",
                        "tenant_definition_mismatch",
                        "definition_mismatch_embeddings_v1",
                        null,
                        "v1",
                        "default",
                        1536,
                        "cosine",
                        "uint64",
                        "BUILDING",
                        "CREATING",
                        null,
                        null,
                        null,
                        "N",
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("collection vectorDim does not match column dimension: 1536 != 768");
    }

    @Test
    void shouldRejectIndexWhenCollectionBelongsToDifferentColumn() {
        var firstColumn = registerActiveColumn("index_owner_first");
        var secondColumn = registerActiveColumn("index_owner_second");
        var collection = registerReadyCollection(firstColumn, "index_owner_first_embeddings_v1");

        assertThatThrownBy(() -> registerVectorIndexUseCase.register(
                new RegisterVectorIndexUseCase.RegisterVectorIndexCommand(
                        secondColumn.columnId(),
                        collection.collectionId(),
                        "wrong_owner_profile",
                        "HNSW",
                        "COSINE",
                        null,
                        null,
                        null,
                        "Y",
                        "ONLINE",
                        "READY",
                        "v1"
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("vector collection does not belong to column: " + collection.collectionId());
    }

    @Test
    void shouldRejectPayloadFieldWhenColumnDoesNotExist() {
        assertThatThrownBy(() -> registerVectorPayloadFieldUseCase.register(
                new RegisterVectorPayloadFieldUseCase.RegisterVectorPayloadFieldCommand(
                        999L,
                        "doc_type",
                        "docType",
                        "KEYWORD",
                        "Y",
                        "Y",
                        "Y",
                        "Y",
                        "ACTIVE",
                        null
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("vector column not found: 999");
    }

    @Test
    void shouldRejectSyncProgressWhenJobBelongsToDifferentColumn() {
        var firstColumn = registerActiveColumn("progress_owner_first");
        var secondColumn = registerActiveColumn("progress_owner_second");
        var collection = registerReadyCollection(firstColumn, "progress_owner_first_embeddings_v1");
        var index = registerReadyIndex(firstColumn, collection, "progress_owner_profile");
        var syncJob = createReadySyncJob(firstColumn, collection, index, "progress-owner-job");

        assertThatThrownBy(() -> upsertVectorSyncProgressUseCase.upsert(
                new UpsertVectorSyncProgressUseCase.UpsertVectorSyncProgressCommand(
                        null,
                        syncJob.jobId(),
                        secondColumn.columnId(),
                        "p0",
                        null,
                        null,
                        0L,
                        0L,
                        0L,
                        "RUNNING",
                        null
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("vector sync job does not belong to column: " + syncJob.jobId());
    }

    @Test
    void shouldRejectSyncErrorWhenJobDoesNotExist() {
        var column = registerActiveColumn("missing_job_error");

        assertThatThrownBy(() -> recordVectorSyncErrorUseCase.record(
                new RecordVectorSyncErrorUseCase.RecordVectorSyncErrorCommand(
                        999L,
                        column.columnId(),
                        "p0",
                        "100",
                        "UPSERT",
                        "UPSERT",
                        "QDRANT_TIMEOUT",
                        "upsert timed out",
                        null,
                        "missing-job-error",
                        0,
                        null,
                        "OPEN"
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("vector sync job not found: 999");
    }

    @Test
    void shouldRejectNonPositiveCollectionConfigAtServiceLayer() {
        assertThatThrownBy(() -> registerVectorCollectionUseCase.register(
                new RegisterVectorCollectionUseCase.RegisterVectorCollectionCommand(
                        1L,
                        "QDRANT",
                        "tenant_a",
                        "doc_embeddings_v1",
                        null,
                        "v1",
                        "default",
                        768,
                        "cosine",
                        "uint64",
                        "ACTIVE",
                        "READY",
                        0,
                        null,
                        null,
                        "N",
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("shardNumber must be greater than 0");
    }

    @Test
    void shouldRejectNonPositiveIndexCollectionIdAtServiceLayer() {
        assertThatThrownBy(() -> registerVectorIndexUseCase.register(
                new RegisterVectorIndexUseCase.RegisterVectorIndexCommand(
                        1L,
                        0L,
                        "default_profile",
                        "HNSW",
                        "COSINE",
                        null,
                        null,
                        null,
                        "Y",
                        "ONLINE",
                        "READY",
                        "v1"
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("collectionId must be greater than 0");
    }

    @Test
    void shouldRejectNonPositiveSyncJobReferencesAtServiceLayer() {
        assertThatThrownBy(() -> createVectorSyncJobUseCase.create(
                new CreateVectorSyncJobUseCase.CreateVectorSyncJobCommand(
                        1L,
                        1L,
                        -1L,
                        "BACKFILL",
                        "MANUAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        ))
                .isInstanceOf(BizException.class)
                .hasMessage("indexId must be greater than 0");
    }

    private VectorColumnMeta registerActiveColumn(String suffix) {
        return registerVectorColumnUseCase.register(
                new RegisterVectorColumnUseCase.RegisterVectorColumnCommand(
                        "tenant_" + suffix,
                        "public",
                        "doc_" + suffix,
                        "id",
                        "embedding",
                        768,
                        "cosine",
                        "full_and_incremental",
                        "ACTIVE",
                        null,
                        null
                )
        );
    }

    private VectorCollectionMeta registerReadyCollection(VectorColumnMeta column, String collectionName) {
        return registerVectorCollectionUseCase.register(
                new RegisterVectorCollectionUseCase.RegisterVectorCollectionCommand(
                        column.columnId(),
                        "QDRANT",
                        column.tenantId(),
                        collectionName,
                        collectionName + "_active",
                        "v1",
                        "default",
                        column.dimension(),
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
    }

    private VectorIndexMeta registerReadyIndex(VectorColumnMeta column, VectorCollectionMeta collection, String profileName) {
        return registerVectorIndexUseCase.register(
                new RegisterVectorIndexUseCase.RegisterVectorIndexCommand(
                        column.columnId(),
                        collection.collectionId(),
                        profileName,
                        "HNSW",
                        column.metricType(),
                        null,
                        null,
                        null,
                        "Y",
                        "ONLINE",
                        "READY",
                        "v1"
                )
        );
    }

    private VectorSyncJobMeta createReadySyncJob(
            VectorColumnMeta column,
            VectorCollectionMeta collection,
            VectorIndexMeta index,
            String idempotencyKey
    ) {
        return createVectorSyncJobUseCase.create(
                new CreateVectorSyncJobUseCase.CreateVectorSyncJobCommand(
                        column.columnId(),
                        collection.collectionId(),
                        index.indexId(),
                        "BACKFILL",
                        "MANUAL",
                        idempotencyKey,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );
    }
}
