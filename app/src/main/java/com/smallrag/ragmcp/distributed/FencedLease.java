package com.smallrag.ragmcp.distributed;

import java.time.Duration;
import java.util.Optional;

/**
 * 带 fencing token 的租约凭证。
 *
 * <p>获取锁时由锁实现生成单调 token（standalone 每 key 计数器，distributed 使用
 * Redisson {@code RFencedLock}）。写入方在每次 Qdrant 变更前调用 {@link #isValid()}：
 * 只有“锁仍被持有且 token 仍最新”才允许写入。
 *
 * <p>凭证不可变，可安全跨线程使用；{@link #release()} 幂等。
 *
 * @author zjk
 */
public final class FencedLease {

    private final DistributedLock lock;
    private final LockHandle handle;

    private FencedLease(DistributedLock lock, LockHandle handle) {
        this.lock = lock;
        this.handle = handle;
    }

    /**
     * 获取锁并返回凭证；获取失败返回空。
     *
     * @param lock  锁实现
     * @param key   锁 key（已包含前缀）
     * @param owner 持有者标识
     * @param lease 租约时长（distributed 下由 Redisson watchdog 续租）
     * @return 凭证；锁已被占用时为空
     */
    public static Optional<FencedLease> acquire(DistributedLock lock, String key, String owner,
            Duration lease) {
        return lock.tryAcquire(key, owner, lease)
                .map(handle -> new FencedLease(lock, handle));
    }

    /**
     * 续租；只有仍是当前持有者时成功。
     *
     * @param lease 新的租约时长
     * @return true 表示续租成功
     */
    public boolean renew(Duration lease) {
        return lock.renew(handle, lease);
    }

    /**
     * 写栅栏：远程校验持有者 + fencing token 仍最新。
     *
     * @return true 表示可以继续写入
     */
    public boolean isValid() {
        try {
            return lock.isHeld(handle);
        } catch (RuntimeException e) {
            // Redis 不可用时按“持有权已丢失”处理，fail-closed
            return false;
        }
    }

    /**
     * 释放租约；幂等。
     */
    public void release() {
        try {
            lock.release(handle);
        } catch (RuntimeException ignored) {
            // 释放失败不影响调用方；锁会由 watchdog / TTL 过期回收
        }
    }

    /**
     * @return 持有者标识
     */
    public String owner() {
        return handle.owner();
    }

    /**
     * @return 本次持有的 fencing token
     */
    public long epoch() {
        return handle.fencingToken();
    }

    /**
     * @return 锁 key
     */
    public String key() {
        return handle.key();
    }
}
