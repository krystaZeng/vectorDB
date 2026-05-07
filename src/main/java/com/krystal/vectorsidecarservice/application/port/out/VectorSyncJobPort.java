package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.sync.VectorSyncJobMeta;

import java.util.List;

public interface VectorSyncJobPort {

    VectorSyncJobMeta save(VectorSyncJobMeta syncJobMeta);

    List<VectorSyncJobMeta> findByColumnId(long columnId);
}
