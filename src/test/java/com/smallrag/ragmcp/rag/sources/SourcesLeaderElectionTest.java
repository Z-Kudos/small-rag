package com.smallrag.ragmcp.rag.sources;

import com.smallrag.ragmcp.config.DistributedProperties;
import com.smallrag.ragmcp.distributed.DistributedLock;
import com.smallrag.ragmcp.distributed.LocalDistributedLock;
import com.smallrag.ragmcp.distributed.LockHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SourcesLeaderElectionTest {

    private SourcesLeaderElection first;
    private SourcesLeaderElection second;

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
    void onlyOneReplicaIsLeaderAndTakeoverWorks() {
        LocalDistributedLock shared = new LocalDistributedLock();
        first = new SourcesLeaderElection(shared, properties());
        second = new SourcesLeaderElection(shared, properties());

        first.tick();
        second.tick();

        assertThat(first.isLeader()).isTrue();
        assertThat(second.isLeader()).isFalse();

        first.shutdown();
        second.tick();

        assertThat(second.isLeader()).isTrue();
    }

    @Test
    void failedRenewalDropsLeadership() {
        FailingRenewLock lock = new FailingRenewLock();
        first = new SourcesLeaderElection(lock, properties());

        first.tick();
        assertThat(first.isLeader()).isTrue();

        lock.renewable = false;
        first.tick();

        assertThat(first.isLeader()).isFalse();
    }

    @Test
    void acquireFailureKeepsReplicaFollower() {
        DistributedLock failing = new DistributedLock() {
            @Override
            public Optional<LockHandle> tryAcquire(String key, String owner, Duration lease) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public boolean renew(LockHandle handle, Duration lease) {
                return false;
            }

            @Override
            public void release(LockHandle handle) {
            }

            @Override
            public boolean isHeld(LockHandle handle) {
                return false;
            }

            @Override
            public Optional<String> currentOwner(String key) {
                return Optional.empty();
            }
        };
        first = new SourcesLeaderElection(failing, properties());

        first.tick();

        assertThat(first.isLeader()).isFalse();
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
            return delegate.isHeld(handle);
        }

        @Override
        public Optional<String> currentOwner(String key) {
            return delegate.currentOwner(key);
        }
    }
}
