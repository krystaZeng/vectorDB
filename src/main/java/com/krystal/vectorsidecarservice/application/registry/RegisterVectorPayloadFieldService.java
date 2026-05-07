package com.krystal.vectorsidecarservice.application.registry;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorPayloadFieldUseCase;
import com.krystal.vectorsidecarservice.application.port.out.VectorPayloadFieldPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.common.id.IdGenerator;
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

    private final VectorPayloadFieldPort vectorPayloadFieldPort;
    private final IdGenerator idGenerator;

    @Override
    public VectorPayloadFieldMeta register(RegisterVectorPayloadFieldCommand command) {
        FieldValidator.requirePositive(command.columnId(), "columnId");
        Instant now = Instant.now();
        VectorPayloadFieldMeta meta = new VectorPayloadFieldMeta(
                idGenerator.nextId(),
                command.columnId(),
                FieldValidator.requireText(command.sourceColumnName(), "sourceColumnName"),
                FieldValidator.requireText(command.payloadKey(), "payloadKey"),
                FieldValidator.optionalText(command.fieldType(), "KEYWORD").toUpperCase(Locale.ROOT),
                FieldValidator.normalizeFlag(command.isFilterable(), "Y", "isFilterable"),
                FieldValidator.normalizeFlag(command.isReturnable(), "Y", "isReturnable"),
                FieldValidator.normalizeFlag(command.isIndexed(), "Y", "isIndexed"),
                FieldValidator.normalizeFlag(command.syncEnabled(), "Y", "syncEnabled"),
                FieldValidator.normalizeEnum(command.fieldStatus(), Set.of("ACTIVE", "DISABLED"), "ACTIVE", "fieldStatus"),
                FieldValidator.optionalTextWithMaxLength(command.indexParamsJson(), "indexParamsJson", MAX_PAYLOAD_INDEX_PARAMS_LEN),
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
