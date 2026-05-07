package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.sync.VectorSyncProgressMeta;

import java.util.List;

public interface VectorSyncProgressPort {

    VectorSyncProgressMeta upsert(VectorSyncProgressMeta syncProgressMeta);

    List<VectorSyncProgressMeta> findByJobId(long jobId);
}
