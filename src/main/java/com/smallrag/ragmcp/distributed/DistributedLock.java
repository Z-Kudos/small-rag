package com.smallrag.ragmcp.distributed;

import java.time.Duration;
import java.util.Optional;

/**
 * 跨副本互斥锁抽象。
 *
 * <p>standalone 使用进程内实现；distributed 使用 Redis 租约锁。
 * 实现必须是线程安全的，并且释放/续租只能由当前持有者完成。
 *
 * @author zjk
 */
public interface DistributedLock {

    /**
     * 尝试获取锁。
     *
     * @param key   锁 key（已包含前缀）
     * @param owner 持有者标识
     * @param lease 租约时长
     * @return 获取成功时返回凭证，否则为空
     */
    Optional<LockHandle> tryAcquire(String key, String owner, Duration lease);

    /**
     * 续租；只有凭证仍对应当前持有者时才成功。
     *
     * @param handle 凭证
     * @param lease  新的租约时长
     * @return true 表示续租成功
     */
    boolean renew(LockHandle handle, Duration lease);

    /**
     * 释放；只有凭证仍对应当前持有者时才删除。
     *
     * @param handle 凭证
     */
    void release(LockHandle handle);

    /**
     * 查询当前持有者（用于诊断/接管判断）。
     *
     * @param key 锁 key
     * @return 持有者标识；未持有或已过期时为空
     */
    Optional<String> currentOwner(String key);
}
