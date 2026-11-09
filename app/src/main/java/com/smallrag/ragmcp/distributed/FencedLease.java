package com.smallrag.ragmcp.distributed;

import java.time.Duration;
import java.util.Optional;

/**
 * 带栅栏 epoch 的租约凭证。
 *
 * <p>获取锁后立即递增该 key 的 epoch，因此每次接管都会得到严格更大的 epoch。
 * 写入方在每次 Qdrant 变更前调用 {@link #isValid()}：只有“仍是当前持有者”
 * 且“epoch 未落后”时才允许写入，旧持有者恢复后即使本地租约尚未过期也会被拒绝。
 *
 * <p>凭证不可变，可安全跨线程使用；{@link #release()} 幂等。
 *
 * @author zjk
 */
public final class FencedLease {

    private final DistributedLock lock;
    private final LockHandle handle;
    private final long epoch;

    private FencedLease(DistributedLock lock, LockHandle handle, long epoch) {
        this.lock = lock;
        this.handle = handle;
        this.epoch = epoch;
    }

    /**
     * 获取锁并生成新的 epoch；获取失败返回空，获取后递增 epoch 失败会先释放锁再抛出。
     *
     * @param lock  锁实现
     * @param key   锁 key（已包含前缀）
     * @param owner 持有者标识
     * @param lease 租约时长
     * @return 凭证；锁已被占用时为空
     */
    public static Optional<FencedLease> acquire(DistributedLock lock, String key, String owner,
            Duration lease) {
        Optional<LockHandle> acquired = lock.tryAcquire(key, owner, lease);
        if (acquired.isEmpty()) {
            return Optional.empty();
        }
        LockHandle handle = acquired.get();
        try {
            return Optional.of(new FencedLease(lock, handle, lock.nextEpoch(key)));
        } catch (RuntimeException e) {
            lock.release(handle);
            throw e;
        }
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
     * 写栅栏：远程校验持有者 + token，并确认 epoch 仍是最新。
     *
     * @return true 表示可以继续写入
     */
    public boolean isValid() {
        return lock.isHeld(handle) && lock.currentEpoch(handle.key()) == epoch;
    }

    /**
     * 释放租约；幂等。
     */
    public void release() {
        lock.release(handle);
    }

    /**
     * @return 持有者标识
     */
    public String owner() {
        return handle.owner();
    }

    /**
     * @return 本次持有的栅栏 epoch
     */
    public long epoch() {
        return epoch;
    }

    /**
     * @return 锁 key
     */
    public String key() {
        return handle.key();
    }
}
