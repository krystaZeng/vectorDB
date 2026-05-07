package com.krystal.vectorsidecarservice.application.registry.lifecycle;

import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VectorCollectionLifecycleService {

    private final VectorCollectionPort vectorCollectionPort;

    public void markCreating(long collectionId) {
        mark(collectionId, VectorCollectionLifecycle.CREATING);
    }

    public void markReady(long collectionId) {
        mark(collectionId, VectorCollectionLifecycle.READY);
    }

    public void markDeprecated(long collectionId) {
        mark(collectionId, VectorCollectionLifecycle.DEPRECATED);
    }

    public void markFailed(long collectionId) {
        mark(collectionId, VectorCollectionLifecycle.FAILED);
    }

    public void markDropped(long collectionId) {
        mark(collectionId, VectorCollectionLifecycle.DROPPED);
    }

    public void mark(long collectionId, VectorCollectionLifecycle target) {
        FieldValidator.requirePositive(collectionId, "collectionId");
        if (target == null) {
            throw new BizException("target collection lifecycle must not be null");
        }
        VectorCollectionMeta currentMeta = vectorCollectionPort.findById(collectionId)
                .orElseThrow(() -> new BizException("vector collection not found: " + collectionId));
        VectorCollectionLifecycle current = VectorCollectionLifecycle.fromPersisted(
                currentMeta.servingState(),
                currentMeta.collectionStatus()
        );
        if (!current.canTransitionTo(target)) {
            throw new BizException("invalid collection lifecycle transition: "
                    + current.display() + " -> " + target.display());
        }
        vectorCollectionPort.updateStatus(collectionId, target.servingState(), target.collectionStatus());
    }
}
