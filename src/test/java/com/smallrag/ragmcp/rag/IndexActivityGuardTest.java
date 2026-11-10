package com.smallrag.ragmcp.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndexActivityGuardTest {

    private final IndexActivityGuard guard = new IndexActivityGuard();

    @Test
    void onlyOneActivityCanHoldTheLock() {
        assertThat(guard.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();
        assertThat(guard.tryAcquire(IndexActivityGuard.reindex("task-1"))).isFalse();
        assertThat(guard.owner()).contains(IndexActivityGuard.SOURCES);
    }

    @Test
    void releaseAllowsAnotherActivity() {
        assertThat(guard.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();

        guard.release(IndexActivityGuard.SOURCES);

        assertThat(guard.owner()).isEmpty();
        assertThat(guard.tryAcquire(IndexActivityGuard.reindex("task-1"))).isTrue();
        assertThat(guard.isHeldBy(IndexActivityGuard.reindex("task-1"))).isTrue();
        assertThat(guard.activeOwner()).contains(IndexActivityGuard.reindex("task-1"));
    }

    @Test
    void nonOwnerReleaseIsIgnored() {
        assertThat(guard.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();

        guard.release(IndexActivityGuard.reindex("task-1"));

        assertThat(guard.owner()).contains(IndexActivityGuard.SOURCES);
    }

    @Test
    void blankLabelIsRejected() {
        assertThat(guard.tryAcquire(" ")).isFalse();
        assertThat(guard.tryAcquire(null)).isFalse();
        assertThat(guard.owner()).isEmpty();
    }

    @Test
    void isHeldByComparesLabel() {
        assertThat(guard.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();

        assertThat(guard.isHeldBy(IndexActivityGuard.SOURCES)).isTrue();
        assertThat(guard.isHeldBy(IndexActivityGuard.reindex("task-1"))).isFalse();
    }
}
