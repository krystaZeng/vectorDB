package com.krystal.vectorsidecarservice.application.registry.lifecycle;

import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VectorColumnLifecycleService {

    private final VectorMetadataPort vectorMetadataPort;

    public void markBuilding(long columnId, String remark) {
        mark(columnId, VectorColumnLifecycle.BUILDING, remark);
    }

    public void markActive(long columnId) {
        mark(columnId, VectorColumnLifecycle.ACTIVE, null);
    }

    public void markFailed(long columnId, String remark) {
        mark(columnId, VectorColumnLifecycle.FAILED, remark);
    }

    public void markDisabled(long columnId, String remark) {
        mark(columnId, VectorColumnLifecycle.DISABLED, remark);
    }

    public void mark(long columnId, VectorColumnLifecycle target, String remark) {
        FieldValidator.requirePositive(columnId, "columnId");
        if (target == null) {
            throw new BizException("target column lifecycle must not be null");
        }
        VectorColumnMeta currentMeta = vectorMetadataPort.findById(columnId)
                .orElseThrow(() -> new BizException("vector column not found: " + columnId));
        VectorColumnLifecycle current = VectorColumnLifecycle.normalize(currentMeta.status());
        if (!current.canTransitionTo(target)) {
            throw new BizException("invalid column lifecycle transition: "
                    + current.status() + " -> " + target.status());
        }
        vectorMetadataPort.updateStatus(columnId, target.status(), remark);
    }
}
