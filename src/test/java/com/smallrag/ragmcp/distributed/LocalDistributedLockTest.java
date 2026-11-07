package com.smallrag.ragmcp.distributed;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDistributedLockTest {

    private final LocalDistributedLock lock = new LocalDistributedLock();

    @Test
    void onlyOneOwnerCanAcquire() {
        Optional<LockHandle> first = lock.tryAcquire("k", "a", Duration.ofSeconds(30));
        Optional<LockHandle> second = lock.tryAcquire("k", "b", Duration.ofSeconds(30));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(lock.currentOwner("k")).contains("a");
    }

    @Test
    void releaseAllowsAnotherOwnerToAcquire() {
        LockHandle first = lock.tryAcquire("k", "a", Duration.ofSeconds(30)).orElseThrow();

        lock.release(first);

        assertThat(lock.currentOwner("k")).isEmpty();
        assertThat(lock.tryAcquire("k", "b", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void staleHandleCannotReleaseOrRenewNewOwner() {
        LockHandle old = lock.tryAcquire("k", "a", Duration.ofMillis(20)).orElseThrow();
        sleep(40);
        LockHandle fresh = lock.tryAcquire("k", "b", Duration.ofSeconds(30)).orElseThrow();

        lock.release(old);
        assertThat(lock.currentOwner("k")).contains("b");
        assertThat(lock.renew(old, Duration.ofSeconds(30))).isFalse();
        assertThat(lock.renew(fresh, Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void expiredLeaseCanBeTakenOver() {
        lock.tryAcquire("k", "a", Duration.ofMillis(20)).orElseThrow();
        sleep(40);

        assertThat(lock.currentOwner("k")).isEmpty();
        assertThat(lock.tryAcquire("k", "b", Duration.ofSeconds(30))).isPresent();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
