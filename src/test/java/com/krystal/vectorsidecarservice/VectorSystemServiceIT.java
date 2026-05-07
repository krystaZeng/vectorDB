package com.krystal.vectorsidecarservice;

import com.krystal.vectorsidecarservice.application.port.in.CreateVectorSyncJobUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RecordVectorSyncErrorUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorColumnUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorCollectionUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorIndexUseCase;
import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorPayloadFieldUseCase;
import com.krystal.vectorsidecarservice.application.port.in.UpsertVectorSyncProgressUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
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
                        "full_and_incremental"
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
}
