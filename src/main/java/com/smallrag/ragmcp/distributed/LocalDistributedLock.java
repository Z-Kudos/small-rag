package com.smallrag.ragmcp.distributed;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 进程内锁实现，用于 standalone 单容器模式与单元测试。
 *
 * <p>与 Redis 实现保持相同的租约语义：过期后允许其他持有者获取。进程退出时
 * 所有锁自然消失，因此不需要额外清理。
 *
 * @author zjk
 */
public final class LocalDistributedLock implements DistributedLock {

    private record Entry(LockHandle handle) {
    }

    private final Map<String, Entry> locks = new HashMap<>();

    @Override
    public synchronized Optional<LockHandle> tryAcquire(String key, String owner, Duration lease) {
        long now = System.currentTimeMillis();
        Entry current = locks.get(key);
        if (current != null && current.handle().expiresAtMillis() > now) {
            return Optional.empty();
        }
        LockHandle handle = LockHandle.create(key, owner, lease);
        locks.put(key, new Entry(handle));
        return Optional.of(handle);
    }

    @Override
    public synchronized boolean renew(LockHandle handle, Duration lease) {
        long now = System.currentTimeMillis();
        Entry current = locks.get(handle.key());
        if (current == null || current.handle().expiresAtMillis() <= now
                || !current.handle().value().equals(handle.value())) {
            return false;
        }
        locks.put(handle.key(), new Entry(new LockHandle(
                handle.key(), handle.owner(), handle.token(), now + Math.max(1, lease.toMillis()))));
        return true;
    }

    @Override
    public synchronized void release(LockHandle handle) {
        Entry current = locks.get(handle.key());
        if (current != null && current.handle().value().equals(handle.value())) {
            locks.remove(handle.key());
        }
    }

    @Override
    public synchronized boolean isHeld(LockHandle handle) {
        Entry current = locks.get(handle.key());
        return current != null
                && current.handle().expiresAtMillis() > System.currentTimeMillis()
                && current.handle().value().equals(handle.value());
    }

    @Override
    public synchronized Optional<String> currentOwner(String key) {
        Entry current = locks.get(key);
        if (current == null || current.handle().expiresAtMillis() <= System.currentTimeMillis()) {
            locks.remove(key);
            return Optional.empty();
        }
        return Optional.of(current.handle().owner());
    }
}
