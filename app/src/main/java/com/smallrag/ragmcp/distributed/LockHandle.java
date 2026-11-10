package com.smallrag.ragmcp.distributed;

import java.time.Duration;

/**
 * 一次分布式锁持有凭证。
 *
 * <p>{@code token} 是单调递增的 fencing token：standalone 用每 key 计数器，
 * distributed 使用 Redisson {@code RFencedLock} 的 token。释放/校验时用它判断
 * 写入者是否仍是最新持有者，避免旧持有者恢复后误删新持有者的锁。
 *
 * @param key            锁 key
 * @param owner          持有者标识（例如 {@code sources} 或 {@code reindex:<taskId>}）
 * @param token          本次持有的 fencing token（十进制字符串）
 * @param expiresAtMillis 本地估算的租约到期时间（毫秒时间戳，诊断用）
 * @author zjk
 */
public record LockHandle(String key, String owner, String token, long expiresAtMillis) {

    private static final char SEPARATOR = '|';

    /**
     * 创建凭证。
     *
     * @param key          锁 key
     * @param owner        持有者
     * @param lease        租约时长（诊断用）
     * @param fencingToken 单调递增的围栏 token
     * @return 凭证
     */
    public static LockHandle create(String key, String owner, Duration lease, long fencingToken) {
        if (fencingToken <= 0) {
            throw new IllegalArgumentException("fencingToken 必须为正数");
        }
        return new LockHandle(key, owner, Long.toString(fencingToken),
                System.currentTimeMillis() + Math.max(1, lease.toMillis()));
    }

    /**
     * 写入传统 Redis 值时使用的 {@code owner|token}；当前 Redisson 实现不再写该值。
     *
     * @return 存储值
     */
    public String value() {
        return owner + SEPARATOR + token;
    }

    /**
     * @return 围栏 token（数值）
     */
    public long fencingToken() {
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("fencing token 不是数字: " + token, e);
        }
    }
}
