package com.smallrag.ragmcp.distributed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RFencedLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedissonDistributedLockTest {

    private static final String KEY = "smallrag:test:lock";
    private static final String OWNER_KEY = KEY + ":owner";

    @Mock
    private RedissonClient redisson;

    @Mock
    private RFencedLock rLock;

    @Mock
    private RBucket<String> ownerBucket;

    private RedissonDistributedLock lock;

    @BeforeEach
    void setUp() {
        lock = new RedissonDistributedLock(redisson);
        lenient().when(redisson.getFencedLock(KEY)).thenReturn(rLock);
        lenient().when(redisson.<String>getBucket(OWNER_KEY)).thenReturn(ownerBucket);
    }

    @Test
    void acquiresLockAndStoresOwnerDiagnostic() {
        when(rLock.tryLockAndGetToken(0, -1, TimeUnit.MILLISECONDS)).thenReturn(42L);

        Optional<LockHandle> handle = lock.tryAcquire(KEY, "sources", Duration.ofSeconds(30));

        assertThat(handle).isPresent();
        assertThat(handle.get().owner()).isEqualTo("sources");
        assertThat(handle.get().fencingToken()).isEqualTo(42L);
        verify(ownerBucket).set(eq("sources"), eq(30_000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void returnsEmptyWhenLockIsBusy() {
        when(rLock.tryLockAndGetToken(0, -1, TimeUnit.MILLISECONDS)).thenReturn(null);

        assertThat(lock.tryAcquire(KEY, "sources", Duration.ofSeconds(30))).isEmpty();
        verify(ownerBucket, never()).set(eq("sources"), eq(30_000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void isHeldRequiresLatestTokenAndLockedState() {
        LockHandle handle = LockHandle.create(KEY, "sources", Duration.ofSeconds(30), 42L);
        when(rLock.getToken()).thenReturn(42L);
        when(rLock.isLocked()).thenReturn(true);

        assertThat(lock.isHeld(handle)).isTrue();

        when(rLock.getToken()).thenReturn(43L);
        assertThat(lock.isHeld(handle)).isFalse();

        when(rLock.getToken()).thenReturn(42L);
        when(rLock.isLocked()).thenReturn(false);
        assertThat(lock.isHeld(handle)).isFalse();
    }

    @Test
    void releaseOnlyAffectsLatestHolder() {
        LockHandle handle = LockHandle.create(KEY, "sources", Duration.ofSeconds(30), 42L);
        when(rLock.getToken()).thenReturn(42L);
        when(rLock.isLocked()).thenReturn(true);
        when(rLock.forceUnlock()).thenReturn(true);

        lock.release(handle);

        verify(rLock).forceUnlock();
        verify(ownerBucket).delete();

        when(rLock.getToken()).thenReturn(43L);
        lock.release(handle);
        verify(rLock, org.mockito.Mockito.times(1)).forceUnlock();
    }

    @Test
    void renewRefreshesDiagnosticOnlyForCurrentHolder() {
        LockHandle handle = LockHandle.create(KEY, "sources", Duration.ofSeconds(30), 42L);
        when(rLock.getToken()).thenReturn(42L);
        when(rLock.isLocked()).thenReturn(true);

        assertThat(lock.renew(handle, Duration.ofSeconds(30))).isTrue();
        verify(ownerBucket).set(eq("sources"), eq(30_000L), eq(TimeUnit.MILLISECONDS));

        when(rLock.getToken()).thenReturn(43L);
        assertThat(lock.renew(handle, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    void currentOwnerFallsBackToLockWhenDiagnosticIsMissing() {
        when(ownerBucket.get()).thenReturn(null);
        when(rLock.isLocked()).thenReturn(true);
        when(rLock.getToken()).thenReturn(42L);

        assertThat(lock.currentOwner(KEY)).contains("redisson:42");

        when(rLock.isLocked()).thenReturn(false);
        assertThat(lock.currentOwner(KEY)).isEmpty();
    }
}
