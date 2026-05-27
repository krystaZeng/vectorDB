package com.krystal.vectorsidecarservice.application.registry.lifecycle;

import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class VectorColumnLifecycleService {

    private static final int MAX_REMARK_LEN = 1024;

    private final VectorMetadataPort vectorMetadataPort;

    public void markBuilding(long columnId, String remark) {
        mark(columnId, VectorColumnLifecycle.BUILDING, remark);
    }

    public void markActive(long columnId) {
        mark(columnId, VectorColumnLifecycle.ACTIVE, null);
    }

    public boolean markActiveIfCurrentIn(long columnId, Set<String> currentStatuses) {
        FieldValidator.requirePositive(columnId, "columnId");
        return vectorMetadataPort.updateStatusIfCurrentIn(
                columnId,
                VectorColumnLifecycle.ACTIVE.status(),
                null,
                currentStatuses
        ) == 1;
    }

    public void markFailed(long columnId, String remark) {
        mark(columnId, VectorColumnLifecycle.FAILED, remark);
    }

    public boolean markFailedIfCurrentIn(long columnId, String remark, Set<String> currentStatuses) {
        FieldValidator.requirePositive(columnId, "columnId");
        return vectorMetadataPort.updateStatusIfCurrentIn(
                columnId,
                VectorColumnLifecycle.FAILED.status(),
                truncateRemark(remark),
                currentStatuses
        ) == 1;
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

    private String truncateRemark(String remark) {
        if (remark == null || remark.isBlank()) {
            return "readiness verification failed";
        }
        String normalized = remark.trim();
        return normalized.length() <= MAX_REMARK_LEN ? normalized : normalized.substring(0, MAX_REMARK_LEN);
    }
}
