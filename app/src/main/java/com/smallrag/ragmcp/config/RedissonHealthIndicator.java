package com.smallrag.ragmcp.config;

import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Redisson 健康检查：distributed 模式下确认 Redis 可用。
 *
 * <p>standalone 不创建 Redisson 客户端，也不暴露该组件。
 *
 * @author zjk
 */
@Component
@ConditionalOnProperty(prefix = "smallrag.distributed", name = "enabled", havingValue = "true")
public class RedissonHealthIndicator implements HealthIndicator {

    private final RedissonClient redisson;

    public RedissonHealthIndicator(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public Health health() {
        try {
            long keys = redisson.getKeys().count();
            return Health.up()
                    .withDetail("redis", "UP")
                    .withDetail("keys", keys)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("redis", "DOWN")
                    .withDetail("error", e.toString())
                    .build();
        }
    }
}
