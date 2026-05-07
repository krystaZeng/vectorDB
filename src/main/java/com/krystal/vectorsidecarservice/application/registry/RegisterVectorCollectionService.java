package com.krystal.vectorsidecarservice.application.registry;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorCollectionUseCase;
import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.common.id.IdGenerator;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RegisterVectorCollectionService implements RegisterVectorCollectionUseCase {

    private static final String DEFAULT_ENGINE_TYPE = "QDRANT";
    private static final int MAX_COLLECTION_JSON_LEN = 4000;

    private final VectorCollectionPort vectorCollectionPort;
    private final IdGenerator idGenerator;

    @Override
    public VectorCollectionMeta register(RegisterVectorCollectionCommand command) {
        FieldValidator.requirePositive(command.columnId(), "columnId");
        if (command.vectorDim() == null || command.vectorDim() <= 0) {
            throw new BizException("vectorDim must be greater than 0");
        }
        Instant now = Instant.now();
        VectorCollectionMeta meta = new VectorCollectionMeta(
                idGenerator.nextId(),
                command.columnId(),
                FieldValidator.optionalText(command.engineType(), DEFAULT_ENGINE_TYPE).toUpperCase(Locale.ROOT),
                FieldValidator.requireText(command.namespaceName(), "namespaceName"),
                FieldValidator.requireText(command.collectionName(), "collectionName"),
                FieldValidator.optionalText(command.aliasName()),
                FieldValidator.optionalText(command.collectionVersion(), "v1"),
                FieldValidator.normalizeEnum(command.servingState(), Set.of("BUILDING", "ACTIVE", "DEPRECATED"), "ACTIVE", "servingState"),
                FieldValidator.normalizeEnum(command.collectionStatus(), Set.of("CREATING", "READY", "FAILED", "DROPPED"), "READY", "collectionStatus"),
                FieldValidator.optionalText(command.qdrantVectorName(), "default"),
                FieldValidator.normalizeEnum(command.qdrantIdType(), Set.of("UINT64", "UUID"), "UINT64", "qdrantIdType"),
                FieldValidator.normalizeEnum(command.distanceMetric(), Set.of("COSINE", "EUCLID", "DOT"), "COSINE", "distanceMetric"),
                command.vectorDim(),
                command.shardNumber(),
                command.replicationFactor(),
                command.writeConsistencyFactor(),
                FieldValidator.normalizeFlag(command.onDiskPayload(), "N", "onDiskPayload"),
                FieldValidator.optionalTextWithMaxLength(command.hnswConfigJson(), "hnswConfigJson", MAX_COLLECTION_JSON_LEN),
                FieldValidator.optionalTextWithMaxLength(command.quantizationConfigJson(), "quantizationConfigJson", MAX_COLLECTION_JSON_LEN),
                FieldValidator.optionalTextWithMaxLength(command.collectionConfigJson(), "collectionConfigJson", MAX_COLLECTION_JSON_LEN),
                now,
                now
        );
        return vectorCollectionPort.save(meta);
    }

    @Override
    public List<VectorCollectionMeta> listByColumnId(long columnId) {
        FieldValidator.requirePositive(columnId, "columnId");
        return vectorCollectionPort.findByColumnId(columnId);
    }
}
