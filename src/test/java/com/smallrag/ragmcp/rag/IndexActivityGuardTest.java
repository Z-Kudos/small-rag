package com.smallrag.ragmcp.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author zjk
 */
class IndexActivityGuardTest {

    @Test
    void releaseMatchesLabelByValue() {
        IndexActivityGuard guard = new IndexActivityGuard();
        assertThat(guard.tryAcquire(new String("sources"))).isTrue();
        assertThat(guard.tryAcquire("reindex:task-1")).isFalse();

        guard.release(new String("sources"));

        assertThat(guard.owner()).isEmpty();
        assertThat(guard.tryAcquire("reindex:task-1")).isTrue();
    }
}
