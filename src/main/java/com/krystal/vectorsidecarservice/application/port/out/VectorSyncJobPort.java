package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.sync.VectorSyncJobMeta;

import java.util.List;
import java.util.Optional;

public interface VectorSyncJobPort {

    VectorSyncJobMeta save(VectorSyncJobMeta syncJobMeta);

    Optional<VectorSyncJobMeta> findById(long jobId);

    List<VectorSyncJobMeta> findByColumnId(long columnId);
}
