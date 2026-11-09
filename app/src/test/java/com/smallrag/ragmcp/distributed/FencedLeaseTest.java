package com.smallrag.ragmcp.distributed;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author zjk
 */
class FencedLeaseTest {

    private final LocalDistributedLock lock = new LocalDistributedLock();

    @Test
    void acquireBumpsEpochAndValidates() {
        FencedLease first = FencedLease.acquire(lock, "reindex-task", "task-1",
                Duration.ofSeconds(30)).orElseThrow();

        assertThat(first.epoch()).isEqualTo(1L);
        assertThat(first.owner()).isEqualTo("task-1");
        assertThat(first.isValid()).isTrue();
        assertThat(FencedLease.acquire(lock, "reindex-task", "task-2", Duration.ofSeconds(30)))
                .isEmpty();
    }

    @Test
    void staleLeaseIsRejectedAfterTakeover() {
        FencedLease first = FencedLease.acquire(lock, "reindex-task", "task-1",
                Duration.ofSeconds(30)).orElseThrow();

        // 模拟持有者失联：租约被释放后由另一任务接管
        first.release();
        FencedLease second = FencedLease.acquire(lock, "reindex-task", "task-2",
                Duration.ofSeconds(30)).orElseThrow();

        assertThat(second.epoch()).isEqualTo(2L);
        assertThat(second.isValid()).isTrue();
        // 旧持有者恢复后必须被拒绝
        assertThat(first.isValid()).isFalse();
    }

    @Test
    void releaseIsIdempotent() {
        FencedLease lease = FencedLease.acquire(lock, "reindex-task", "task-1",
                Duration.ofSeconds(30)).orElseThrow();

        lease.release();
        lease.release();

        assertThat(lease.isValid()).isFalse();
        assertThat(FencedLease.acquire(lock, "reindex-task", "task-2", Duration.ofSeconds(30)))
                .isPresent();
    }

    @Test
    void renewKeepsLeaseAlive() {
        FencedLease lease = FencedLease.acquire(lock, "reindex-task", "task-1",
                Duration.ofMillis(20)).orElseThrow();

        assertThat(lease.renew(Duration.ofSeconds(30))).isTrue();
        assertThat(lease.isValid()).isTrue();
    }

    @Test
    void acquireReleasesLockWhenEpochFails() {
        LocalDistributedLock delegate = new LocalDistributedLock();
        DistributedLock broken = new DistributedLock() {
            @Override
            public Optional<LockHandle> tryAcquire(String key, String owner, Duration lease) {
                return delegate.tryAcquire(key, owner, lease);
            }

            @Override
            public boolean renew(LockHandle handle, Duration lease) {
                return delegate.renew(handle, lease);
            }

            @Override
            public void release(LockHandle handle) {
                delegate.release(handle);
            }

            @Override
            public boolean isHeld(LockHandle handle) {
                return delegate.isHeld(handle);
            }

            @Override
            public Optional<String> currentOwner(String key) {
                return delegate.currentOwner(key);
            }

            @Override
            public long nextEpoch(String key) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public long currentEpoch(String key) {
                return delegate.currentEpoch(key);
            }
        };

        assertThatThrownBy(() -> FencedLease.acquire(broken, "k", "owner", Duration.ofSeconds(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis down");
        assertThat(delegate.currentOwner("k")).isEmpty();
    }

    @Test
    void emptyWhenLockIsHeld() {
        Optional<LockHandle> handle = lock.tryAcquire("k", "other", Duration.ofSeconds(30));
        assertThat(handle).isPresent();

        assertThat(FencedLease.acquire(lock, "k", "task-1", Duration.ofSeconds(30))).isEmpty();
    }
}
