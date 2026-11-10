package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.DistributedProperties;
import com.smallrag.ragmcp.distributed.RedissonDistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

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

    private RedissonClient redissonA;
    private RedissonClient redissonB;
    private IndexActivityGuard first;
    private IndexActivityGuard second;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + PORT).setDatabase(14);
        config.setLockWatchdogTimeout(Duration.ofSeconds(2).toMillis());
        redissonA = org.redisson.Redisson.create(config);
        redissonB = org.redisson.Redisson.create(config);
    }

    @AfterEach
    void tearDown() {
        if (first != null) {
            first.shutdown();
        }
        if (second != null) {
            second.shutdown();
        }
        if (redissonA != null) {
            redissonA.shutdown();
        }
        if (redissonB != null) {
            redissonB.shutdown();
        }
    }

    @Test
    void onlyOneReplicaWritesAndLeaseTakeoverWorks() {
        DistributedProperties properties = properties(Duration.ofSeconds(2), Duration.ofMillis(200));
        first = new IndexActivityGuard(new RedissonDistributedLock(redissonA), properties);
        second = new IndexActivityGuard(new RedissonDistributedLock(redissonB), properties);

        assertThat(first.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();
        assertThat(second.tryAcquire(IndexActivityGuard.SOURCES)).isFalse();

        // 持有者优雅停机会释放锁；崩溃时靠 Redisson watchdog 停止续租 + TTL 接管
        first.shutdown();
        assertThat(second.tryAcquire(IndexActivityGuard.SOURCES)).isTrue();
        assertThat(second.isHeldBy(IndexActivityGuard.SOURCES)).isTrue();
        // 旧持有者的本地状态尚未清理，但远程校验能发现锁已被接管
        assertThat(first.isHeldBy(IndexActivityGuard.SOURCES)).isFalse();
    }

    @Test
    void renewalKeepsLeaseAliveBeyondNominalExpiry() {
        DistributedProperties properties = properties(Duration.ofSeconds(2), Duration.ofMillis(200));
        first = new IndexActivityGuard(new RedissonDistributedLock(redissonA), properties);
        second = new IndexActivityGuard(new RedissonDistributedLock(redissonB), properties);

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
