package com.krystal.vectorsidecarservice.application.support;

import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorIndexPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorSyncJobPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;
import com.krystal.vectorsidecarservice.domain.sync.VectorSyncJobMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VectorMetadataReferenceGuard {

    private final VectorMetadataPort vectorMetadataPort;
    private final VectorCollectionPort vectorCollectionPort;
    private final VectorIndexPort vectorIndexPort;
    private final VectorSyncJobPort vectorSyncJobPort;

    public VectorColumnMeta requireColumnWritable(long columnId) {
        VectorColumnMeta column = vectorMetadataPort.findById(columnId)
                .orElseThrow(() -> new BizException("vector column not found: " + columnId));
        if ("DISABLED".equals(column.status())) {
            throw new BizException("vector column is DISABLED: " + columnId);
        }
        return column;
    }

    public VectorCollectionMeta requireCollectionForColumn(long collectionId, long columnId) {
        VectorCollectionMeta collection = vectorCollectionPort.findById(collectionId)
                .orElseThrow(() -> new BizException("vector collection not found: " + collectionId));
        if (collection.columnId() != columnId) {
            throw new BizException("vector collection does not belong to column: " + collectionId);
        }
        return collection;
    }

    public VectorIndexMeta requireIndexForColumn(long indexId, long columnId) {
        VectorIndexMeta index = vectorIndexPort.findById(indexId)
                .orElseThrow(() -> new BizException("vector index not found: " + indexId));
        if (index.columnId() != columnId) {
            throw new BizException("vector index does not belong to column: " + indexId);
        }
        return index;
    }

    public VectorSyncJobMeta requireSyncJobForColumn(long jobId, long columnId) {
        VectorSyncJobMeta syncJob = vectorSyncJobPort.findById(jobId)
                .orElseThrow(() -> new BizException("vector sync job not found: " + jobId));
        if (syncJob.columnId() != columnId) {
            throw new BizException("vector sync job does not belong to column: " + jobId);
        }
        return syncJob;
    }

    public void requireCollectionDefinitionMatchesColumn(
            VectorColumnMeta column,
            int vectorDim,
            String distanceMetric
    ) {
        if (vectorDim != column.dimension()) {
            throw new BizException("collection vectorDim does not match column dimension: "
                    + vectorDim + " != " + column.dimension());
        }
        String expectedDistanceMetric = toCollectionDistanceMetric(column.metricType());
        if (!expectedDistanceMetric.equals(distanceMetric)) {
            throw new BizException("collection distanceMetric does not match column metricType: "
                    + distanceMetric + " != " + expectedDistanceMetric);
        }
    }

    public void requireIndexDefinitionMatchesColumn(
            VectorColumnMeta column,
            Long collectionId,
            String metricType
    ) {
        if (!column.metricType().equals(metricType)) {
            throw new BizException("index metricType does not match column metricType: "
                    + metricType + " != " + column.metricType());
        }
        if (collectionId != null) {
            VectorCollectionMeta collection = requireCollectionForColumn(collectionId, column.columnId());
            requireCollectionDefinitionMatchesColumn(column, collection.vectorDim(), collection.distanceMetric());
        }
    }

    public void requireIndexCollectionMatches(Long collectionId, VectorIndexMeta index) {
        if (collectionId != null && index.collectionId() != null && !collectionId.equals(index.collectionId())) {
            throw new BizException("vector index does not belong to collection: " + index.indexId());
        }
    }

    private String toCollectionDistanceMetric(String metricType) {
        return switch (metricType) {
            case "COSINE" -> "COSINE";
            case "L2" -> "EUCLID";
            case "IP" -> "DOT";
            default -> throw new BizException("unsupported metricType for collection: " + metricType);
        };
    }
}
