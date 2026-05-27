package com.krystal.vectorsidecarservice.application.support;

import com.krystal.vectorsidecarservice.application.port.out.VectorEngineAdminPort;
import com.krystal.vectorsidecarservice.application.system.VectorEngineAdminRouter;
import com.krystal.vectorsidecarservice.domain.registry.VectorCollectionMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VectorCollectionReadinessVerifier {

    private final VectorEngineAdminRouter vectorEngineAdminRouter;

    public void verifyOrThrow(VectorCollectionMeta collection) {
        VectorEngineAdminPort adminPort = vectorEngineAdminRouter.get(collection.engineType());
        adminPort.verifyCollection(
                new VectorEngineAdminPort.VerifyCollectionCommand(
                        collection.collectionName(),
                        collection.vectorDim(),
                        collection.distanceMetric(),
                        collection.qdrantVectorName()
                )
        );
        if (collection.aliasName() != null && !collection.aliasName().isBlank()) {
            adminPort.verifyAlias(
                    new VectorEngineAdminPort.VerifyAliasCommand(
                            collection.aliasName(),
                            collection.collectionName()
                    )
            );
        }
    }
}
