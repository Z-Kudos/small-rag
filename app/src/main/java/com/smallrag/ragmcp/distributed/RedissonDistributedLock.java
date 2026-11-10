package com.smallrag.ragmcp.distributed;

import org.redisson.api.RBucket;
import org.redisson.api.RFencedLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson {@code RFencedLock} 的跨副本互斥锁。
 *
 * <p>{@code RFencedLock} 在每次获取时返回严格递增的 fencing token；写入方在
 * 每次 Qdrant 变更前调用 {@link #isHeld(LockHandle)}，只有“锁仍被持有且 token
 * 仍最新”才允许写入。旧持有者恢复后即使本地租约尚未过期，也会因 token 落后
 * 被拒绝。
 *
 * <p>锁由 Redisson watchdog 自动续租；{@link #renew(LockHandle, Duration)} 只做
 * 状态校验与诊断信息刷新，不直接续租。任务锁与目录扫描 leader 都复用此实现。
 *
 * <p>{@code <lock-key>:owner} 只是诊断镜像，记录当前持有者实例标识；权威判定
 * 永远依赖 Redisson 锁本身与 fencing token。
 *
 * @author zjk
 */
public final class RedissonDistributedLock implements DistributedLock {

    private static final String OWNER_SUFFIX = ":owner";

    private final RedissonClient redisson;

    /**
     * @param redisson Redisson 客户端（分布式模式由配置类创建）
     */
    public RedissonDistributedLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String key, String owner, Duration lease) {
        RFencedLock lock = fenced(key);
        // waitTime=0：不等待；leaseTime=-1：由 Redisson watchdog 自动续租
        Long token = lock.tryLockAndGetToken(0, -1, TimeUnit.MILLISECONDS);
        if (token == null) {
            return Optional.empty();
        }
        try {
            ownerBucket(key).set(owner, Math.max(1, lease.toMillis()), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // 诊断镜像写入失败时释放刚获取的锁，避免调用方以为未获取却留下孤儿锁
            try {
                lock.forceUnlock();
            } catch (RuntimeException releaseError) {
                e.addSuppressed(releaseError);
            }
            throw e;
        }
        return Optional.of(LockHandle.create(key, owner, lease, token));
    }

    @Override
    public boolean renew(LockHandle handle, Duration lease) {
        if (!isHeld(handle)) {
            return false;
        }
        // watchdog 会自动续租；这里刷新诊断镜像便于跨副本观察持有者
        ownerBucket(handle.key()).set(handle.owner(), Math.max(1, lease.toMillis()),
                TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void release(LockHandle handle) {
        if (!isHeld(handle)) {
            return;
        }
        if (fenced(handle.key()).forceUnlock()) {
            ownerBucket(handle.key()).delete();
        }
    }

    @Override
    public boolean isHeld(LockHandle handle) {
        RFencedLock lock = fenced(handle.key());
        Long latest = lock.getToken();
        return latest != null && latest == handle.fencingToken() && lock.isLocked();
    }

    @Override
    public Optional<String> currentOwner(String key) {
        RBucket<String> bucket = ownerBucket(key);
        String owner = bucket.get();
        if (owner != null && !owner.isBlank()) {
            return Optional.of(owner);
        }
        RFencedLock lock = fenced(key);
        if (!lock.isLocked()) {
            return Optional.empty();
        }
        Long token = lock.getToken();
        return Optional.of(token == null ? "redisson:locked" : "redisson:" + token);
    }

    private RFencedLock fenced(String key) {
        return redisson.getFencedLock(key);
    }

    private RBucket<String> ownerBucket(String key) {
        return redisson.getBucket(key + OWNER_SUFFIX);
    }
}
