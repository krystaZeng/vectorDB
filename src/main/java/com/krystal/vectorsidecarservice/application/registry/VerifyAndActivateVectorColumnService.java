package com.krystal.vectorsidecarservice.application.registry;

import com.krystal.vectorsidecarservice.application.port.in.VerifyAndActivateVectorColumnUseCase;
import com.krystal.vectorsidecarservice.application.port.out.RelationalSchemaPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorMetadataPort;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorColumnLifecycle;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorColumnLifecycleService;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.application.support.VectorCollectionReadinessVerifier;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class VerifyAndActivateVectorColumnService implements VerifyAndActivateVectorColumnUseCase {

    private static final Set<String> ACTIVATABLE_STATUSES = Set.of(
            VectorColumnLifecycle.BUILDING.status(),
            VectorColumnLifecycle.FAILED.status()
    );

    private final VectorMetadataPort vectorMetadataPort;
    private final VectorCollectionPort vectorCollectionPort;
    private final RelationalSchemaPort relationalSchemaPort;
    private final VectorCollectionReadinessVerifier collectionReadinessVerifier;
    private final VectorColumnLifecycleService vectorColumnLifecycleService;

    @Override
    public VerifyAndActivateVectorColumnResult verifyAndActivate(VerifyAndActivateVectorColumnCommand command) {
        if (command == null) {
            throw new BizException("request must not be null");
        }
        FieldValidator.requirePositive(command.columnId(), "columnId");
        VectorColumnMeta column = vectorMetadataPort.findById(command.columnId())
                .orElseThrow(() -> new BizException("vector column not found: " + command.columnId()));
        VectorColumnLifecycle lifecycle = VectorColumnLifecycle.normalize(column.status());
        if (lifecycle == VectorColumnLifecycle.ACTIVE) {
            return new VerifyAndActivateVectorColumnResult(
                    column.columnId(),
                    column.status(),
                    column.status(),
                    false,
                    "vector column is already ACTIVE"
            );
        }
        if (lifecycle == VectorColumnLifecycle.DISABLED) {
            throw new BizException("vector column is DISABLED: " + column.columnId());
        }

        try {
            verifyRelationalShape(column);
            VectorCollectionMeta collection = readyCollection(column.columnId());
            collectionReadinessVerifier.verifyOrThrow(collection);
        } catch (RuntimeException ex) {
            vectorColumnLifecycleService.markFailedIfCurrentIn(
                    column.columnId(),
                    ex.getMessage(),
                    ACTIVATABLE_STATUSES
            );
            throw ex;
        }

        boolean activated = vectorColumnLifecycleService.markActiveIfCurrentIn(
                column.columnId(),
                ACTIVATABLE_STATUSES
        );
        if (activated) {
            return new VerifyAndActivateVectorColumnResult(
                    column.columnId(),
                    column.status(),
                    VectorColumnLifecycle.ACTIVE.status(),
                    true,
                    "vector column verified and activated"
            );
        }

        VectorColumnMeta latest = vectorMetadataPort.findById(column.columnId())
                .orElseThrow(() -> new BizException("vector column not found after activation attempt: "
                        + column.columnId()));
        if (VectorColumnLifecycle.ACTIVE.status().equals(latest.status())) {
            return new VerifyAndActivateVectorColumnResult(
                    latest.columnId(),
                    column.status(),
                    latest.status(),
                    false,
                    "vector column is already ACTIVE"
            );
        }
        throw new BizException("vector column status changed during activation: "
                + column.status() + " -> " + latest.status());
    }

    private void verifyRelationalShape(VectorColumnMeta column) {
        if (!relationalSchemaPort.tableExists(column.schemaName(), column.tableName())) {
            throw new BizException("table does not exist: " + column.schemaName() + "." + column.tableName());
        }
        if (!relationalSchemaPort.columnExists(column.schemaName(), column.tableName(), column.pkColumn())) {
            throw new BizException("pk column does not exist: "
                    + column.schemaName() + "." + column.tableName() + "." + column.pkColumn());
        }
        if (!relationalSchemaPort.columnExists(column.schemaName(), column.tableName(), column.vectorColumn())) {
            throw new BizException("vector column does not exist: "
                    + column.schemaName() + "." + column.tableName() + "." + column.vectorColumn());
        }
    }

    private VectorCollectionMeta readyCollection(long columnId) {
        return vectorCollectionPort.findByColumnId(columnId)
                .stream()
                .filter(collection -> "ACTIVE".equals(collection.servingState())
                        && "READY".equals(collection.collectionStatus()))
                .findFirst()
                .orElseThrow(() -> new BizException("ready vector collection not found for column: " + columnId));
    }
}
