package com.krystal.vectorsidecarservice.application.port.in;

import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;

import java.util.List;

public interface RegisterVectorColumnUseCase {

    VectorColumnMeta register(RegisterVectorColumnCommand command);

    List<VectorColumnMeta> list();

    record RegisterVectorColumnCommand(
            String tenantId,
            String schemaName,
            String tableName,
            String pkColumn,
            String vectorColumn,
            int dimension,
            String metricType,
            String vectorEncoding,
            String syncMode,
            String status,
            String definitionHash,
            String remark
    ) {
        public RegisterVectorColumnCommand(
                String tenantId,
                String schemaName,
                String tableName,
                String pkColumn,
                String vectorColumn,
                int dimension,
                String metricType,
                String syncMode,
                String status,
                String definitionHash,
                String remark
        ) {
            this(
                    tenantId,
                    schemaName,
                    tableName,
                    pkColumn,
                    vectorColumn,
                    dimension,
                    metricType,
                    null,
                    syncMode,
                    status,
                    definitionHash,
                    remark
            );
        }
    }
}
