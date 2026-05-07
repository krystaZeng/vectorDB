package com.krystal.vectorsidecarservice.application.registry;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorIndexUseCase;
import com.krystal.vectorsidecarservice.application.port.out.VectorIndexPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.common.id.IdGenerator;
import com.krystal.vectorsidecarservice.domain.registry.VectorIndexMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RegisterVectorIndexService implements RegisterVectorIndexUseCase {

    private static final int MAX_INDEX_JSON_LEN = 4000;

    private final VectorIndexPort vectorIndexPort;
    private final IdGenerator idGenerator;

    @Override
    public VectorIndexMeta register(RegisterVectorIndexCommand command) {
        FieldValidator.requirePositive(command.columnId(), "columnId");
        Instant now = Instant.now();
        VectorIndexMeta meta = new VectorIndexMeta(
                idGenerator.nextId(),
                command.columnId(),
                command.collectionId(),
                FieldValidator.requireText(command.profileName(), "profileName"),
                FieldValidator.optionalText(command.indexType(), "HNSW").toUpperCase(Locale.ROOT),
                FieldValidator.normalizeEnum(command.metricType(), Set.of("COSINE", "L2", "IP"), "COSINE", "metricType"),
                FieldValidator.optionalTextWithMaxLength(command.indexParamsJson(), "indexParamsJson", MAX_INDEX_JSON_LEN),
                FieldValidator.optionalTextWithMaxLength(command.searchParamsJson(), "searchParamsJson", MAX_INDEX_JSON_LEN),
                FieldValidator.optionalTextWithMaxLength(command.payloadIndexJson(), "payloadIndexJson", MAX_INDEX_JSON_LEN),
                FieldValidator.normalizeFlag(command.isDefault(), "N", "isDefault"),
                FieldValidator.normalizeEnum(command.servingState(), Set.of("ONLINE", "OFFLINE", "CANARY"), "ONLINE", "servingState"),
                FieldValidator.normalizeEnum(command.indexStatus(), Set.of("CREATING", "READY", "FAILED", "REBUILDING"), "READY", "indexStatus"),
                FieldValidator.optionalText(command.buildVersion()),
                now,
                now
        );
        return vectorIndexPort.save(meta);
    }

    @Override
    public List<VectorIndexMeta> listByColumnId(long columnId) {
        FieldValidator.requirePositive(columnId, "columnId");
        return vectorIndexPort.findByColumnId(columnId);
    }
}
