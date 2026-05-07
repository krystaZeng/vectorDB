package com.krystal.vectorsidecarservice.application.registry.lifecycle;

import com.krystal.vectorsidecarservice.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorLifecycleTest {

    @Test
    void shouldResolveValidColumnLifecycle() {
        assertThat(VectorColumnLifecycle.normalize("building"))
                .isEqualTo(VectorColumnLifecycle.BUILDING);
        assertThat(VectorColumnLifecycle.normalize("active"))
                .isEqualTo(VectorColumnLifecycle.ACTIVE);
    }

    @Test
    void shouldConstrainColumnLifecycleTransitions() {
        assertThat(VectorColumnLifecycle.BUILDING.canTransitionTo(VectorColumnLifecycle.ACTIVE)).isTrue();
        assertThat(VectorColumnLifecycle.BUILDING.canTransitionTo(VectorColumnLifecycle.FAILED)).isTrue();
        assertThat(VectorColumnLifecycle.FAILED.canTransitionTo(VectorColumnLifecycle.BUILDING)).isTrue();
        assertThat(VectorColumnLifecycle.FAILED.canTransitionTo(VectorColumnLifecycle.ACTIVE)).isFalse();
        assertThat(VectorColumnLifecycle.DISABLED.canTransitionTo(VectorColumnLifecycle.ACTIVE)).isFalse();
    }

    @Test
    void shouldResolveValidCollectionLifecycleCombination() {
        assertThat(VectorCollectionLifecycle.normalize(null, null))
                .isEqualTo(VectorCollectionLifecycle.READY);
        assertThat(VectorCollectionLifecycle.normalize("building", "creating"))
                .isEqualTo(VectorCollectionLifecycle.CREATING);
        assertThat(VectorCollectionLifecycle.normalize("deprecated", "ready"))
                .isEqualTo(VectorCollectionLifecycle.DEPRECATED);
    }

    @Test
    void shouldRejectInvalidCollectionLifecycleCombination() {
        assertThatThrownBy(() -> VectorCollectionLifecycle.normalize("ACTIVE", "FAILED"))
                .isInstanceOf(BizException.class)
                .hasMessage("collection lifecycle has invalid state combination: servingState=ACTIVE, collectionStatus=FAILED");
    }

    @Test
    void shouldConstrainCollectionLifecycleTransitions() {
        assertThat(VectorCollectionLifecycle.CREATING.canTransitionTo(VectorCollectionLifecycle.READY)).isTrue();
        assertThat(VectorCollectionLifecycle.CREATING.canTransitionTo(VectorCollectionLifecycle.FAILED)).isTrue();
        assertThat(VectorCollectionLifecycle.FAILED.canTransitionTo(VectorCollectionLifecycle.CREATING)).isTrue();
        assertThat(VectorCollectionLifecycle.FAILED.canTransitionTo(VectorCollectionLifecycle.READY)).isFalse();
        assertThat(VectorCollectionLifecycle.DROPPED.canTransitionTo(VectorCollectionLifecycle.READY)).isFalse();
    }

    @Test
    void shouldResolveValidIndexLifecycleCombination() {
        assertThat(VectorIndexLifecycle.normalize(null, null))
                .isEqualTo(VectorIndexLifecycle.READY);
        assertThat(VectorIndexLifecycle.normalize("offline", "creating"))
                .isEqualTo(VectorIndexLifecycle.CREATING);
        assertThat(VectorIndexLifecycle.normalize("canary", "ready"))
                .isEqualTo(VectorIndexLifecycle.CANARY_READY);
    }

    @Test
    void shouldRejectInvalidIndexLifecycleCombination() {
        assertThatThrownBy(() -> VectorIndexLifecycle.normalize("ONLINE", "CREATING"))
                .isInstanceOf(BizException.class)
                .hasMessage("index lifecycle has invalid state combination: servingState=ONLINE, indexStatus=CREATING");
    }

    @Test
    void shouldConstrainIndexLifecycleTransitions() {
        assertThat(VectorIndexLifecycle.CREATING.canTransitionTo(VectorIndexLifecycle.READY)).isTrue();
        assertThat(VectorIndexLifecycle.READY.canTransitionTo(VectorIndexLifecycle.REBUILDING)).isTrue();
        assertThat(VectorIndexLifecycle.REBUILDING.canTransitionTo(VectorIndexLifecycle.CANARY_READY)).isTrue();
        assertThat(VectorIndexLifecycle.FAILED.canTransitionTo(VectorIndexLifecycle.CREATING)).isTrue();
        assertThat(VectorIndexLifecycle.FAILED.canTransitionTo(VectorIndexLifecycle.READY)).isFalse();
    }
}
