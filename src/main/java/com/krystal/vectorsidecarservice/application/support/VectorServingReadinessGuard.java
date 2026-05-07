package com.krystal.vectorsidecarservice.application.support;

import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorIndexPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorColumnLifecycle;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VectorServingReadinessGuard {

    private final VectorMetadataPort vectorMetadataPort;
    private final VectorCollectionPort vectorCollectionPort;
    private final VectorIndexPort vectorIndexPort;

    public void requireReadyForSync(long columnId, Long collectionId, Long indexId) {
        VectorColumnMeta column = vectorMetadataPort.findById(columnId)
                .orElseThrow(() -> new BizException("vector column not found: " + columnId));
        if (!VectorColumnLifecycle.ACTIVE.status().equals(column.status())) {
            throw new BizException("vector column is not ACTIVE: " + columnId);
        }
        if (collectionId != null) {
            requireReadyCollection(columnId, collectionId);
        }
        if (indexId != null) {
            VectorIndexMeta index = vectorIndexPort.findById(indexId)
                    .orElseThrow(() -> new BizException("vector index not found: " + indexId));
            if (index.columnId() != columnId) {
                throw new BizException("vector index does not belong to column: " + indexId);
            }
            if (collectionId != null && index.collectionId() != null && !collectionId.equals(index.collectionId())) {
                throw new BizException("vector index does not belong to collection: " + indexId);
            }
            if (collectionId == null && index.collectionId() != null) {
                requireReadyCollection(columnId, index.collectionId());
            }
            if (!"ONLINE".equals(index.servingState()) || !"READY".equals(index.indexStatus())) {
                throw new BizException("vector index is not READY: " + indexId);
            }
        }
    }

    private void requireReadyCollection(long columnId, long collectionId) {
        VectorCollectionMeta collection = vectorCollectionPort.findById(collectionId)
                .orElseThrow(() -> new BizException("vector collection not found: " + collectionId));
        if (collection.columnId() != columnId) {
            throw new BizException("vector collection does not belong to column: " + collectionId);
        }
        if (!"ACTIVE".equals(collection.servingState()) || !"READY".equals(collection.collectionStatus())) {
            throw new BizException("vector collection is not READY: " + collectionId);
        }
    }
}
