package com.krystal.vectorsidecarservice.application.registry.lifecycle;

import com.krystal.vectorsidecarservice.application.port.out.VectorIndexPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VectorIndexLifecycleService {

    private final VectorIndexPort vectorIndexPort;

    public void markCreating(long indexId) {
        mark(indexId, VectorIndexLifecycle.CREATING);
    }

    public void markReady(long indexId) {
        mark(indexId, VectorIndexLifecycle.READY);
    }

    public void markOfflineReady(long indexId) {
        mark(indexId, VectorIndexLifecycle.OFFLINE_READY);
    }

    public void markCanaryReady(long indexId) {
        mark(indexId, VectorIndexLifecycle.CANARY_READY);
    }

    public void markRebuilding(long indexId) {
        mark(indexId, VectorIndexLifecycle.REBUILDING);
    }

    public void markFailed(long indexId) {
        mark(indexId, VectorIndexLifecycle.FAILED);
    }

    public void mark(long indexId, VectorIndexLifecycle target) {
        FieldValidator.requirePositive(indexId, "indexId");
        if (target == null) {
            throw new BizException("target index lifecycle must not be null");
        }
        VectorIndexMeta currentMeta = vectorIndexPort.findById(indexId)
                .orElseThrow(() -> new BizException("vector index not found: " + indexId));
        VectorIndexLifecycle current = VectorIndexLifecycle.fromPersisted(
                currentMeta.servingState(),
                currentMeta.indexStatus()
        );
        if (!current.canTransitionTo(target)) {
            throw new BizException("invalid index lifecycle transition: "
                    + current.display() + " -> " + target.display());
        }
        vectorIndexPort.updateStatus(indexId, target.servingState(), target.indexStatus());
    }
}
