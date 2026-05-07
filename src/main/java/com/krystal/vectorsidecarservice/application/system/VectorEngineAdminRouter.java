package com.krystal.vectorsidecarservice.application.system;

import com.krystal.vectorsidecarservice.application.port.out.VectorEngineAdminPort;
import com.krystal.vectorsidecarservice.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class VectorEngineAdminRouter {

    private final Map<String, VectorEngineAdminPort> portsByEngineType;

    public VectorEngineAdminRouter(List<VectorEngineAdminPort> ports) {
        this.portsByEngineType = ports.stream()
                .collect(Collectors.toUnmodifiableMap(
                        port -> normalize(port.engineType()),
                        Function.identity()
                ));
    }

    public VectorEngineAdminPort get(String engineType) {
        String normalized = normalize(engineType);
        VectorEngineAdminPort port = portsByEngineType.get(normalized);
        if (port == null) {
            throw new BizException("vector engine is not supported: " + normalized);
        }
        return port;
    }

    private String normalize(String engineType) {
        if (engineType == null || engineType.isBlank()) {
            throw new BizException("engineType must not be blank");
        }
        return engineType.trim().toUpperCase(Locale.ROOT);
    }
}
