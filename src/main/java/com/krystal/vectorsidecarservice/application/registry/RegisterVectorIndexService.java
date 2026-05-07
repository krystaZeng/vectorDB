package com.krystal.vectorsidecarservice.application.registry;

import com.krystal.vectorsidecarservice.application.port.in.RegisterVectorIndexUseCase;
import com.krystal.vectorsidecarservice.application.port.out.IdGeneratorPort;
import com.krystal.vectorsidecarservice.application.port.out.VectorIndexPort;
import com.krystal.vectorsidecarservice.application.registry.lifecycle.VectorIndexLifecycle;
import com.krystal.vectorsidecarservice.application.support.FieldValidator;
import com.krystal.vectorsidecarservice.application.support.VectorMetadataReferenceGuard;
import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;
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
    private final IdGeneratorPort idGenerator;
    private final VectorMetadataReferenceGuard referenceGuard;

    @Override
    public VectorIndexMeta register(RegisterVectorIndexCommand command) {
        FieldValidator.requirePositive(command.columnId(), "columnId");
        Long collectionId = FieldValidator.optionalPositive(command.collectionId(), "collectionId");
        VectorIndexLifecycle lifecycle = VectorIndexLifecycle.normalize(
                command.servingState(),
                command.indexStatus()
        );
        String profileName = FieldValidator.requireText(command.profileName(), "profileName");
        String indexType = FieldValidator.optionalText(command.indexType(), "HNSW").toUpperCase(Locale.ROOT);
        String metricType = FieldValidator.normalizeEnum(command.metricType(), Set.of("COSINE", "L2", "IP"), "COSINE", "metricType");
        String indexParamsJson = FieldValidator.optionalTextWithMaxLength(command.indexParamsJson(), "indexParamsJson", MAX_INDEX_JSON_LEN);
        String searchParamsJson = FieldValidator.optionalTextWithMaxLength(command.searchParamsJson(), "searchParamsJson", MAX_INDEX_JSON_LEN);
        String payloadIndexJson = FieldValidator.optionalTextWithMaxLength(command.payloadIndexJson(), "payloadIndexJson", MAX_INDEX_JSON_LEN);
        String isDefault = FieldValidator.normalizeFlag(command.isDefault(), "N", "isDefault");
        String buildVersion = FieldValidator.optionalText(command.buildVersion());
        VectorColumnMeta column = referenceGuard.requireColumnWritable(command.columnId());
        referenceGuard.requireIndexDefinitionMatchesColumn(column, collectionId, metricType);
        Instant now = Instant.now();
        VectorIndexMeta meta = new VectorIndexMeta(
                idGenerator.nextId(),
                command.columnId(),
                collectionId,
                profileName,
                indexType,
                metricType,
                indexParamsJson,
                searchParamsJson,
                payloadIndexJson,
                isDefault,
                lifecycle.servingState(),
                lifecycle.indexStatus(),
                buildVersion,
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
