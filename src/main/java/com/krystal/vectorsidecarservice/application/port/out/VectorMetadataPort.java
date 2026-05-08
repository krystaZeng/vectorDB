package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.registry.VectorColumnMeta;

import java.util.List;
import java.util.Optional;

public interface VectorMetadataPort {

    VectorColumnMeta save(VectorColumnMeta meta);

    Optional<VectorColumnMeta> findById(long columnId);

    Optional<VectorColumnMeta> findByIdentity(String tenantId, String schemaName, String tableName, String vectorColumn);

    List<VectorColumnMeta> findByTableIdentity(String tenantId, String schemaName, String tableName);

    List<VectorColumnMeta> findAll();

    void updateStatus(long columnId, String status, String remark);
}
