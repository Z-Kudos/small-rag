package com.smallrag.ragmcp.distributed;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 基于 Redis 的租约锁。
 *
 * <p>获取使用 {@code SET key value NX PX lease}；续租与释放使用 Lua 脚本，
 * 保证“只有当前持有者”才能续租/删除，避免旧持有者误删新锁。
 *
 * @author zjk
 */
public final class RedisDistributedLock implements DistributedLock {

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String key, String owner, Duration lease) {
        LockHandle handle = LockHandle.create(key, owner, lease);
        Boolean acquired = redis.opsForValue().setIfAbsent(key, handle.value(), lease);
        return Boolean.TRUE.equals(acquired) ? Optional.of(handle) : Optional.empty();
    }

    @Override
    public boolean renew(LockHandle handle, Duration lease) {
        Long result = redis.execute(RENEW_SCRIPT, List.of(handle.key()), handle.value(),
                String.valueOf(Math.max(1, lease.toMillis())));
        return result != null && result > 0;
    }

    @Override
    public void release(LockHandle handle) {
        redis.execute(RELEASE_SCRIPT, List.of(handle.key()), handle.value());
    }

    @Override
    public boolean isHeld(LockHandle handle) {
        String value = redis.opsForValue().get(handle.key());
        return handle.value().equals(value);
    }

    @Override
    public Optional<String> currentOwner(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? Optional.empty() : Optional.of(LockHandle.ownerOf(value));
    }
}
