package com.smallrag.ragmcp.distributed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisDistributedLockTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void acquireUsesSetIfAbsentWithLease() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
        RedisDistributedLock lock = new RedisDistributedLock(redis);

        Optional<LockHandle> handle = lock.tryAcquire("k", "sources", Duration.ofSeconds(30));

        assertThat(handle).isPresent();
        assertThat(handle.orElseThrow().value()).startsWith("sources|");
        verify(valueOperations).setIfAbsent(eq("k"), anyString(), eq(Duration.ofSeconds(30)));
    }

    @Test
    void acquireReturnsEmptyWhenKeyExists() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        RedisDistributedLock lock = new RedisDistributedLock(redis);

        assertThat(lock.tryAcquire("k", "sources", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void renewAndReleaseUseTokenCheckedScripts() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any())).thenReturn(1L);
        RedisDistributedLock lock = new RedisDistributedLock(redis);
        LockHandle handle = LockHandle.create("k", "sources", Duration.ofSeconds(30));

        assertThat(lock.renew(handle, Duration.ofSeconds(30))).isTrue();
        lock.release(handle);

        verify(redis).execute(any(DefaultRedisScript.class), anyList(), any(), any());
        verify(redis).execute(any(DefaultRedisScript.class), anyList(), any());
    }

    @Test
    void currentOwnerParsesStoredValue() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("k")).thenReturn("sources|token-1");
        RedisDistributedLock lock = new RedisDistributedLock(redis);

        assertThat(lock.currentOwner("k")).contains("sources");
    }

    @Test
    void isHeldComparesOwnerAndToken() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        RedisDistributedLock lock = new RedisDistributedLock(redis);
        LockHandle handle = LockHandle.create("k", "sources", Duration.ofSeconds(30));
        when(valueOperations.get("k")).thenReturn(handle.value());

        assertThat(lock.isHeld(handle)).isTrue();

        when(valueOperations.get("k")).thenReturn("sources|other-token");
        assertThat(lock.isHeld(handle)).isFalse();

        when(valueOperations.get("k")).thenReturn(null);
        assertThat(lock.isHeld(handle)).isFalse();
    }

    @Test
    void epochUsesIncrAndReadsCounterKey() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("k:epoch")).thenReturn(1L, 2L);
        RedisDistributedLock lock = new RedisDistributedLock(redis);

        assertThat(lock.nextEpoch("k")).isEqualTo(1L);
        assertThat(lock.nextEpoch("k")).isEqualTo(2L);

        when(valueOperations.get("k:epoch")).thenReturn("2");
        assertThat(lock.currentEpoch("k")).isEqualTo(2L);

        when(valueOperations.get("k:epoch")).thenReturn(null);
        assertThat(lock.currentEpoch("k")).isZero();
    }
}
