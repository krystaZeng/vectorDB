package com.krystal.vectorsidecarservice.application.registry;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorPayloadFieldUseCase;
import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorPayloadFieldPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.application.support.VectorMetadataReferenceGuard;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorPayloadFieldMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RegisterVectorPayloadFieldService implements RegisterVectorPayloadFieldUseCase {

    private static final int MAX_PAYLOAD_INDEX_PARAMS_LEN = 2000;
    private static final String SIDECAR_PAYLOAD_PREFIX = "_sidecar_";

    private final VectorPayloadFieldPort vectorPayloadFieldPort;
    private final IdGeneratorPort idGenerator;
    private final VectorMetadataReferenceGuard referenceGuard;

    @Override
    public VectorPayloadFieldMeta register(RegisterVectorPayloadFieldCommand command) {
        FieldValidator.requirePositive(command.columnId(), "columnId");
        String sourceColumnName = FieldValidator.requireText(command.sourceColumnName(), "sourceColumnName");
        String payloadKey = FieldValidator.requireText(command.payloadKey(), "payloadKey");
        String fieldType = FieldValidator.optionalText(command.fieldType(), "KEYWORD").toUpperCase(Locale.ROOT);
        String isFilterable = FieldValidator.normalizeFlag(command.isFilterable(), "Y", "isFilterable");
        String isReturnable = FieldValidator.normalizeFlag(command.isReturnable(), "Y", "isReturnable");
        String isIndexed = FieldValidator.normalizeFlag(command.isIndexed(), "Y", "isIndexed");
        String syncEnabled = FieldValidator.normalizeFlag(command.syncEnabled(), "Y", "syncEnabled");
        String fieldStatus = FieldValidator.normalizeEnum(command.fieldStatus(), Set.of("ACTIVE", "DISABLED"), "ACTIVE", "fieldStatus");
        String indexParamsJson = FieldValidator.optionalTextWithMaxLength(command.indexParamsJson(), "indexParamsJson", MAX_PAYLOAD_INDEX_PARAMS_LEN);
        String payloadIndexStatus = FieldValidator.normalizeEnum(
                command.payloadIndexStatus(),
                Set.of("MISSING", "CREATING", "CREATED", "FAILED"),
                "MISSING",
                "payloadIndexStatus"
        );
        if (payloadKey.toLowerCase(Locale.ROOT).startsWith(SIDECAR_PAYLOAD_PREFIX)) {
            throw new BizException("payloadKey must not start with reserved prefix: " + SIDECAR_PAYLOAD_PREFIX);
        }
        referenceGuard.requireColumnWritable(command.columnId());
        Instant now = Instant.now();
        VectorPayloadFieldMeta meta = new VectorPayloadFieldMeta(
                idGenerator.nextId(),
                command.columnId(),
                sourceColumnName,
                payloadKey,
                fieldType,
                isFilterable,
                isReturnable,
                isIndexed,
                syncEnabled,
                fieldStatus,
                indexParamsJson,
                payloadIndexStatus,
                "CREATED".equals(payloadIndexStatus) ? now : null,
                null,
                null,
                now,
                now
        );
        return vectorPayloadFieldPort.save(meta);
    }

    @Override
    public List<VectorPayloadFieldMeta> listByColumnId(long columnId) {
        FieldValidator.requirePositive(columnId, "columnId");
        return vectorPayloadFieldPort.findByColumnId(columnId);
    }
}
