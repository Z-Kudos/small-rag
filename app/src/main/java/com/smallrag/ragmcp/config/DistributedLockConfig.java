package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.distributed.DistributedLock;
import com.smallrag.ragmcp.distributed.LocalDistributedLock;
import com.smallrag.ragmcp.distributed.RedissonDistributedLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 分布式锁装配：默认进程内实现，`smallrag.distributed.enabled=true` 时切换 Redisson。
 *
 * @author zjk
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DistributedProperties.class, ArchiveProperties.class})
@Slf4j
public class DistributedLockConfig {

    /**
     * Redisson 客户端；只有 distributed 模式才创建。
     *
     * @param properties 分布式配置
     * @return RedissonClient
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "smallrag.distributed", name = "enabled", havingValue = "true")
    public RedissonClient redissonClient(DistributedProperties properties) {
        DistributedProperties.Redis redis = properties.getRedis();
        Config config = new Config();
        config.setLockWatchdogTimeout(properties.getLock().getLease().toMillis());
        SingleServerConfig singleServer = config.useSingleServer()
                .setAddress("redis://" + redis.getHost() + ":" + redis.getPort())
                .setDatabase(redis.getDatabase());
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            config.setPassword(redis.getPassword());
        }
        log.info("分布式模式已启用：使用 Redisson RFencedLock（address=redis://{}:{}, db={}, lease={}）",
                redis.getHost(), redis.getPort(), redis.getDatabase(),
                properties.getLock().getLease());
        return org.redisson.Redisson.create(config);
    }

    /**
     * distributed 模式使用的 Redisson 分布式锁。
     *
     * @param redisson Redisson 客户端
     * @return 分布式锁
     */
    @Bean
    @ConditionalOnProperty(prefix = "smallrag.distributed", name = "enabled", havingValue = "true")
    public DistributedLock redisDistributedLock(RedissonClient redisson) {
        return new RedissonDistributedLock(redisson);
    }

    /**
     * standalone 模式使用的进程内锁。
     *
     * @return 进程内锁
     */
    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    public DistributedLock localDistributedLock() {
        log.info("standalone 模式：使用进程内索引活动锁（无外部依赖）");
        return new LocalDistributedLock();
    }
}
