package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.distributed.DistributedLock;
import com.smallrag.ragmcp.distributed.LocalDistributedLock;
import com.smallrag.ragmcp.distributed.RedisDistributedLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 分布式锁装配：默认进程内实现，`smallrag.distributed.enabled=true` 时切换 Redis。
 *
 * @author zjk
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DistributedProperties.class)
@Slf4j
public class DistributedLockConfig {

    @Bean
    @ConditionalOnProperty(prefix = "smallrag.distributed", name = "enabled", havingValue = "true")
    public DistributedLock redisDistributedLock(StringRedisTemplate redisTemplate,
            DistributedProperties properties) {
        log.info("分布式模式已启用：使用 Redis 租约锁（lease={}, renew={}, prefix={}）",
                properties.getLock().getLease(), properties.getLock().getRenewInterval(),
                properties.getLock().getKeyPrefix());
        return new RedisDistributedLock(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    public DistributedLock localDistributedLock() {
        log.info("standalone 模式：使用进程内索引活动锁（无外部依赖）");
        return new LocalDistributedLock();
    }
}
