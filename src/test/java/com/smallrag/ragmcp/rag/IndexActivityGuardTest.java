package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.DistributedProperties;
import com.smallrag.ragmcp.distributed.DistributedLock;
import com.smallrag.ragmcp.distributed.LocalDistributedLock;
import com.smallrag.ragmcp.distributed.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IndexActivityGuardTest {

    private IndexActivityGuard first;
    private IndexActivityGuard second;

    @AfterEach
    void tearDown() {
        if (first != null) {
            first.shutdown();
        }
        if (second != null) {
            second.shutdown();
        }
    }

    @Test
    void releaseMatchesLabelByValue() {
        first = new IndexActivityGuard();
        assertThat(first.tryAcquire(new String("sources"))).isTrue();
        assertThat(first.tryAcquire("reindex:task-1")).isFalse();

        first.release(new String("sources"));

        assertThat(first.owner()).isEmpty();
        assertThat(first.tryAcquire("reindex:task-1")).isTrue();
    }

    @Test
    void secondInstanceCannotAcquireWhileFirstHolds() {
        DistributedLock shared = new LocalDistributedLock();
        first = new IndexActivityGuard(shared, properties());
        second = new IndexActivityGuard(shared, properties());

        assertThat(first.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();
        assertThat(first.isHeldBy(IndexActivityGuard.SOURCES)).isTrue();
        assertThat(second.tryAcquire(IndexActivityGuard.SOURCES)).isFalse();
        assertThat(second.isHeldBy(IndexActivityGuard.SOURCES)).isFalse();
        // 抢锁失败的副本仍能报告真正持有者
        assertThat(second.owner()).isEmpty();
        assertThat(second.activeOwner()).contains(IndexActivityGuard.SOURCES);

        first.release(IndexActivityGuard.SOURCES);
        assertThat(second.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();
    }

    @Test
    void releaseOnlyAffectsCurrentOwner() {
        first = new IndexActivityGuard();
        assertThat(first.tryAcquire(IndexActivityGuard.reindex("t1"))).isTrue();

        first.release(IndexActivityGuard.reindex("t2"));
        assertThat(first.isHeldBy(IndexActivityGuard.reindex("t1"))).isTrue();

        first.release(IndexActivityGuard.reindex("t1"));
        assertThat(first.owner()).isEmpty();
    }

    @Test
    void failedRenewalMarksLockLost() {
        FailingRenewLock lock = new FailingRenewLock();
        first = new IndexActivityGuard(lock, properties());

        assertThat(first.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();
        lock.renewable = false;

        first.renewLock();

        assertThat(first.isHeldBy(IndexActivityGuard.SOURCES)).isFalse();
        assertThat(first.owner()).isEmpty();
    }

    @Test
    void failedRemoteCheckMarksLockLost() {
        FailingRenewLock lock = new FailingRenewLock();
        first = new IndexActivityGuard(lock, properties());

        assertThat(first.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();
        lock.held = false;

        // 本地续租尚未失败，但远程校验发现锁已被接管/过期
        assertThat(first.isHeldBy(IndexActivityGuard.SOURCES)).isFalse();
        assertThat(first.owner()).isEmpty();
    }

    private static DistributedProperties properties() {
        DistributedProperties properties = new DistributedProperties();
        properties.getLock().setLease(Duration.ofSeconds(30));
        properties.getLock().setRenewInterval(Duration.ofSeconds(10));
        return properties;
    }

    private static final class FailingRenewLock implements DistributedLock {

        private final LocalDistributedLock delegate = new LocalDistributedLock();
        private boolean renewable = true;
        private boolean held = true;

        @Override
        public Optional<LockHandle> tryAcquire(String key, String owner, Duration lease) {
            return delegate.tryAcquire(key, owner, lease);
        }

        @Override
        public boolean renew(LockHandle handle, Duration lease) {
            return renewable && delegate.renew(handle, lease);
        }

        @Override
        public void release(LockHandle handle) {
            delegate.release(handle);
        }

        @Override
        public boolean isHeld(LockHandle handle) {
            return held && delegate.isHeld(handle);
        }

        @Override
        public Optional<String> currentOwner(String key) {
            return delegate.currentOwner(key);
        }

        @Override
        public long nextEpoch(String key) {
            return delegate.nextEpoch(key);
        }

        @Override
        public long currentEpoch(String key) {
            return delegate.currentEpoch(key);
        }
    }
}
