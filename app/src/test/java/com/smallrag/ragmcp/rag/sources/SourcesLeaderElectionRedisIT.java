package com.smallrag.ragmcp.rag.sources;

import com.smallrag.ragmcp.config.DistributedProperties;
import com.smallrag.ragmcp.distributed.DistributedLock;
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
 * 真实 Redis 上的 leader 选举验收：默认跳过。
 *
 * <pre>
 * redis-server --port 6390 --save '' --daemonize yes
 * mvn test -Dtest=SourcesLeaderElectionRedisIT -Dsmallrag.redis.it=true
 * </pre>
 *
 * @author zjk
 */
@EnabledIfSystemProperty(named = "smallrag.redis.it", matches = "true")
class SourcesLeaderElectionRedisIT {

    private static final int PORT = 6390;

    private RedissonClient redissonA;
    private RedissonClient redissonB;
    private DistributedLock lockA;
    private DistributedLock lockB;
    private SourcesLeaderElection first;
    private SourcesLeaderElection second;
    private DistributedProperties properties;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:" + PORT).setDatabase(14);
        config.setLockWatchdogTimeout(Duration.ofSeconds(2).toMillis());
        redissonA = org.redisson.Redisson.create(config);
        redissonB = org.redisson.Redisson.create(config);
        lockA = new RedissonDistributedLock(redissonA);
        lockB = new RedissonDistributedLock(redissonB);
        properties = new DistributedProperties();
        properties.getLock().setLease(Duration.ofSeconds(2));
        properties.getLock().setRenewInterval(Duration.ofMillis(200));
        properties.getLock().setKeyPrefix("smallrag:it:" + UUID.randomUUID() + ":");
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
    void leaseTakeoverAfterLeaderStopsRenewing() {
        first = new SourcesLeaderElection(lockA, properties);
        second = new SourcesLeaderElection(lockB, properties);

        first.tick();
        second.tick();
        assertThat(first.isLeader()).isTrue();
        assertThat(second.isLeader()).isFalse();

        // 持有者优雅停机会释放 leader 锁；崩溃时靠 watchdog 停止续租 + TTL 接管
        first.shutdown();
        second.tick();
        assertThat(second.isLeader()).isTrue();

        // 旧 leader 在下一次 tick 时会发现租约已丢失
        first.tick();
        assertThat(first.isLeader()).isFalse();
    }

    @Test
    void renewalKeepsLeadershipBeyondLease() {
        first = new SourcesLeaderElection(lockA, properties);
        second = new SourcesLeaderElection(lockB, properties);

        first.tick();
        sleep(1200);
        first.tick();
        sleep(1200);
        first.tick();

        assertThat(first.isLeader()).isTrue();
        second.tick();
        assertThat(second.isLeader()).isFalse();
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
