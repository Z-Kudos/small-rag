package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.DistributedProperties;
import com.smallrag.ragmcp.distributed.RedisDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 上的跨副本锁验收测试：默认跳过，手动执行。
 *
 * <pre>
 * redis-server --port 6390 --save '' --daemonize yes
 * mvn test -Dtest=IndexActivityGuardRedisIT -Dsmallrag.redis.it=true
 * </pre>
 *
 * @author zjk
 */
@EnabledIfSystemProperty(named = "smallrag.redis.it", matches = "true")
class IndexActivityGuardRedisIT {

    private static final int PORT = 6390;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private IndexActivityGuard first;
    private IndexActivityGuard second;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1", PORT);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        if (first != null) {
            first.shutdown();
        }
        if (second != null) {
            second.shutdown();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void onlyOneReplicaWritesAndLeaseTakeoverWorks() {
        DistributedProperties properties = properties(Duration.ofSeconds(2), Duration.ofMillis(200));
        first = new IndexActivityGuard(new RedisDistributedLock(redis), properties);
        second = new IndexActivityGuard(new RedisDistributedLock(redis), properties);

        assertThat(first.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();
        assertThat(second.tryAcquire(IndexActivityGuard.SOURCES)).isFalse();

        // 持有者失联（不再续租）：租约到期后另一副本接管
        first.shutdown();
        sleep(2600);
        assertThat(second.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();
        assertThat(second.isHeldBy(IndexActivityGuard.SOURCES)).isTrue();
        // 旧持有者的本地状态尚未清理，但远程校验能立即发现租约已被接管
        assertThat(first.isHeldBy(IndexActivityGuard.SOURCES)).isFalse();
    }

    @Test
    void renewalKeepsLeaseAliveBeyondNominalExpiry() {
        DistributedProperties properties = properties(Duration.ofSeconds(2), Duration.ofMillis(200));
        first = new IndexActivityGuard(new RedisDistributedLock(redis), properties);
        second = new IndexActivityGuard(new RedisDistributedLock(redis), properties);

        assertThat(first.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();
        sleep(2600);

        // 仍在续租，本地持有有效，且另一副本拿不到锁
        assertThat(first.isHeldBy(IndexActivityGuard.SOURCES)).isTrue();
        assertThat(second.tryAcquire(IndexActivityGuard.SOURCES)).isFalse();
    }

    private DistributedProperties properties(Duration lease, Duration renew) {
        DistributedProperties properties = new DistributedProperties();
        properties.getLock().setLease(lease);
        properties.getLock().setRenewInterval(renew);
        // 每个测试用独立 key 前缀，避免相互干扰
        properties.getLock().setKeyPrefix("smallrag:it:" + UUID.randomUUID() + ":");
        return properties;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
