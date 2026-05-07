package com.krystal.vectorsidecarservice.application.port.out;

import com.krystal.vectorsidecarservice.domain.sync.VectorSyncErrorMeta;

import java.util.List;

public interface VectorSyncErrorPort {

    VectorSyncErrorMeta save(VectorSyncErrorMeta syncErrorMeta);

    List<VectorSyncErrorMeta> findByJobId(long jobId);
}
