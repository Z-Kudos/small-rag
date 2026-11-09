package com.smallrag.ragmcp.distributed;

import java.time.Duration;
import java.util.UUID;

/**
 * 一次分布式锁持有凭证。
 *
 * <p>{@code token} 区分同一 owner 的多次持有，释放/续租时必须同时匹配 owner 与 token，
 * 避免旧持有者误删新持有者的锁。
 *
 * @param key            锁 key
 * @param owner          持有者标识（例如 {@code sources} 或 {@code reindex:<taskId>}）
 * @param token          本次持有的随机令牌
 * @param expiresAtMillis 本地记录的租约到期时间（毫秒时间戳）
 * @author zjk
 */
public record LockHandle(String key, String owner, String token, long expiresAtMillis) {

    private static final char SEPARATOR = '|';

    /**
     * 创建新凭证。
     *
     * @param key   锁 key
     * @param owner 持有者
     * @param lease 租约时长
     * @return 凭证
     */
    public static LockHandle create(String key, String owner, Duration lease) {
        return new LockHandle(key, owner, UUID.randomUUID().toString(),
                System.currentTimeMillis() + Math.max(1, lease.toMillis()));
    }

    /**
     * 写入 Redis 的值：{@code owner|token}。
     *
     * @return 存储值
     */
    public String value() {
        return owner + SEPARATOR + token;
    }

    /**
     * 从 Redis 值中解析 owner；值格式不合法时返回原值。
     *
     * @param value 存储值
     * @return owner
     */
    public static String ownerOf(String value) {
        int index = value.indexOf(SEPARATOR);
        return index < 0 ? value : value.substring(0, index);
    }
}
