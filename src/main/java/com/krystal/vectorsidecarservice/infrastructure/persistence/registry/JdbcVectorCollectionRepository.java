package com.krystal.vectorsidecarservice.infrastructure.persistence.registry;

import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.infrastructure.persistence.support.JdbcTimeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcVectorCollectionRepository extends JdbcTimeSupport implements VectorCollectionPort {

    private static final String INSERT_SQL = """
            INSERT INTO SYS_VECTOR_COLLECTIONS_ (
                COLLECTION_ID, COLUMN_ID, ENGINE_TYPE, NAMESPACE_NAME, COLLECTION_NAME, ALIAS_NAME,
                COLLECTION_VERSION, SERVING_STATE, COLLECTION_STATUS, QDRANT_VECTOR_NAME, QDRANT_ID_TYPE,
                DISTANCE_METRIC, VECTOR_DIM, SHARD_NUMBER, REPLICATION_FACTOR, WRITE_CONSISTENCY_FACTOR,
                ON_DISK_PAYLOAD, HNSW_CONFIG_JSON, QUANTIZATION_CONFIG_JSON, COLLECTION_CONFIG_JSON,
                CREATED_AT, UPDATED_AT
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_SQL = """
            SELECT
                COLLECTION_ID, COLUMN_ID, ENGINE_TYPE, NAMESPACE_NAME, COLLECTION_NAME, ALIAS_NAME,
                COLLECTION_VERSION, SERVING_STATE, COLLECTION_STATUS, QDRANT_VECTOR_NAME, QDRANT_ID_TYPE,
                DISTANCE_METRIC, VECTOR_DIM, SHARD_NUMBER, REPLICATION_FACTOR, WRITE_CONSISTENCY_FACTOR,
                ON_DISK_PAYLOAD, HNSW_CONFIG_JSON, QUANTIZATION_CONFIG_JSON, COLLECTION_CONFIG_JSON,
                CREATED_AT, UPDATED_AT
            FROM SYS_VECTOR_COLLECTIONS_
            WHERE COLUMN_ID = ?
            ORDER BY CREATED_AT DESC
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE SYS_VECTOR_COLLECTIONS_
            SET SERVING_STATE = ?, COLLECTION_STATUS = ?, UPDATED_AT = CURRENT_TIMESTAMP
            WHERE COLLECTION_ID = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public VectorCollectionMeta save(VectorCollectionMeta collectionMeta) {
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    collectionMeta.collectionId(),
                    collectionMeta.columnId(),
                    collectionMeta.engineType(),
                    collectionMeta.namespaceName(),
                    collectionMeta.collectionName(),
                    collectionMeta.aliasName(),
                    collectionMeta.collectionVersion(),
                    collectionMeta.servingState(),
                    collectionMeta.collectionStatus(),
                    collectionMeta.qdrantVectorName(),
                    collectionMeta.qdrantIdType(),
                    collectionMeta.distanceMetric(),
                    collectionMeta.vectorDim(),
                    collectionMeta.shardNumber(),
                    collectionMeta.replicationFactor(),
                    collectionMeta.writeConsistencyFactor(),
                    collectionMeta.onDiskPayload(),
                    collectionMeta.hnswConfigJson(),
                    collectionMeta.quantizationConfigJson(),
                    collectionMeta.collectionConfigJson(),
                    timestamp(collectionMeta.createdAt()),
                    timestamp(collectionMeta.updatedAt())
            );
            return collectionMeta;
        } catch (DataIntegrityViolationException ex) {
            throw new BizException("vector collection already registered", ex);
        }
    }

    @Override
    public List<VectorCollectionMeta> findByColumnId(long columnId) {
        return jdbcTemplate.query(FIND_SQL, (rs, rowNum) -> new VectorCollectionMeta(
                rs.getLong("COLLECTION_ID"),
                rs.getLong("COLUMN_ID"),
                rs.getString("ENGINE_TYPE"),
                rs.getString("NAMESPACE_NAME"),
                rs.getString("COLLECTION_NAME"),
                rs.getString("ALIAS_NAME"),
                rs.getString("COLLECTION_VERSION"),
                rs.getString("SERVING_STATE"),
                rs.getString("COLLECTION_STATUS"),
                rs.getString("QDRANT_VECTOR_NAME"),
                rs.getString("QDRANT_ID_TYPE"),
                rs.getString("DISTANCE_METRIC"),
                rs.getInt("VECTOR_DIM"),
                (Integer) rs.getObject("SHARD_NUMBER"),
                (Integer) rs.getObject("REPLICATION_FACTOR"),
                (Integer) rs.getObject("WRITE_CONSISTENCY_FACTOR"),
                rs.getString("ON_DISK_PAYLOAD"),
                rs.getString("HNSW_CONFIG_JSON"),
                rs.getString("QUANTIZATION_CONFIG_JSON"),
                rs.getString("COLLECTION_CONFIG_JSON"),
                instant(rs.getTimestamp("CREATED_AT")),
                instant(rs.getTimestamp("UPDATED_AT"))
        ), columnId);
    }

    @Override
    public void updateStatus(long collectionId, String servingState, String collectionStatus) {
        jdbcTemplate.update(UPDATE_STATUS_SQL, servingState, collectionStatus, collectionId);
    }
}
