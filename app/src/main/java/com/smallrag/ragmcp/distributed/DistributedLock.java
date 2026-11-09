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
     * 远程校验凭证是否仍有效（持有者 + token 完全匹配）。
     *
     * <p>写栅栏在每次写入前调用：本地续租线程可能尚未发现租约丢失，
     * 直接问存储可以避免旧持有者在租约过期后继续写入。
     *
     * @param handle 凭证
     * @return true 表示仍由该凭证持有
     */
    boolean isHeld(LockHandle handle);

    /**
     * 查询当前持有者（用于诊断/接管判断）。
     *
     * @param key 锁 key
     * @return 持有者标识；未持有或已过期时为空
     */
    Optional<String> currentOwner(String key);

    /**
     * 递增并返回栅栏 epoch：新持有者接管时调用，保证 epoch 单调递增。
     *
     * <p>Qdrant 没有条件写，写栅栏用 epoch 判断“写入者是否仍是最新持有者”：
     * 旧持有者恢复后即使本地租约还没过期，epoch 也已落后，必须拒绝写入。
     *
     * @param key 锁 key
     * @return 递增后的 epoch
     */
    long nextEpoch(String key);

    /**
     * 读取当前栅栏 epoch；从未有持有者时返回 0。
     *
     * @param key 锁 key
     * @return 当前 epoch
     */
    long currentEpoch(String key);
}
