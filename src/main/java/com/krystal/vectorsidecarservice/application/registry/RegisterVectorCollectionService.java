package com.krystal.vectorsidecarservice.application.registry;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorCollectionUseCase;
import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorCollectionPort;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorCollectionLifecycle;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.application.support.VectorCollectionReadinessVerifier;
import com.krystal.vectorsidecarservice.application.support.VectorMetadataReferenceGuard;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
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
    private final IdGeneratorPort idGenerator;
    private final VectorMetadataReferenceGuard referenceGuard;
    private final VectorCollectionReadinessVerifier collectionReadinessVerifier;

    @Override
    public VectorCollectionMeta register(RegisterVectorCollectionCommand command) {
        FieldValidator.requirePositive(command.columnId(), "columnId");
        if (command.vectorDim() == null || command.vectorDim() <= 0) {
            throw new BizException("vectorDim must be greater than 0");
        }
        VectorCollectionLifecycle lifecycle = VectorCollectionLifecycle.normalize(
                command.servingState(),
                command.collectionStatus()
        );
        Integer shardNumber = FieldValidator.optionalPositive(command.shardNumber(), "shardNumber");
        Integer replicationFactor = FieldValidator.optionalPositive(command.replicationFactor(), "replicationFactor");
        Integer writeConsistencyFactor = FieldValidator.optionalPositive(
                command.writeConsistencyFactor(),
                "writeConsistencyFactor"
        );
        String engineType = FieldValidator.optionalText(command.engineType(), DEFAULT_ENGINE_TYPE).toUpperCase(Locale.ROOT);
        String namespaceName = FieldValidator.requireText(command.namespaceName(), "namespaceName");
        String collectionName = FieldValidator.requireText(command.collectionName(), "collectionName");
        String aliasName = FieldValidator.optionalText(command.aliasName());
        String collectionVersion = FieldValidator.optionalText(command.collectionVersion(), "v1");
        String qdrantVectorName = FieldValidator.optionalText(command.qdrantVectorName(), "default");
        String qdrantIdType = FieldValidator.normalizeEnum(command.qdrantIdType(), Set.of("UINT64", "UUID"), "UINT64", "qdrantIdType");
        String distanceMetric = FieldValidator.normalizeEnum(command.distanceMetric(), Set.of("COSINE", "EUCLID", "DOT"), "COSINE", "distanceMetric");
        String onDiskPayload = FieldValidator.normalizeFlag(command.onDiskPayload(), "N", "onDiskPayload");
        String hnswConfigJson = FieldValidator.optionalTextWithMaxLength(command.hnswConfigJson(), "hnswConfigJson", MAX_COLLECTION_JSON_LEN);
        String quantizationConfigJson = FieldValidator.optionalTextWithMaxLength(command.quantizationConfigJson(), "quantizationConfigJson", MAX_COLLECTION_JSON_LEN);
        String collectionConfigJson = FieldValidator.optionalTextWithMaxLength(command.collectionConfigJson(), "collectionConfigJson", MAX_COLLECTION_JSON_LEN);
        VectorColumnMeta column = referenceGuard.requireColumnWritable(command.columnId());
        referenceGuard.requireCollectionDefinitionMatchesColumn(column, command.vectorDim(), distanceMetric);
        Instant now = Instant.now();
        VectorCollectionMeta meta = new VectorCollectionMeta(
                idGenerator.nextId(),
                command.columnId(),
                engineType,
                namespaceName,
                collectionName,
                aliasName,
                collectionVersion,
                lifecycle.servingState(),
                lifecycle.collectionStatus(),
                qdrantVectorName,
                qdrantIdType,
                distanceMetric,
                command.vectorDim(),
                shardNumber,
                replicationFactor,
                writeConsistencyFactor,
                onDiskPayload,
                hnswConfigJson,
                quantizationConfigJson,
                collectionConfigJson,
                now,
                now
        );
        if ("READY".equals(meta.collectionStatus())) {
            collectionReadinessVerifier.verifyOrThrow(meta);
        }
        return vectorCollectionPort.save(meta);
    }

    @Override
    public List<VectorCollectionMeta> listByColumnId(long columnId) {
        FieldValidator.requirePositive(columnId, "columnId");
        return vectorCollectionPort.findByColumnId(columnId);
    }
}
