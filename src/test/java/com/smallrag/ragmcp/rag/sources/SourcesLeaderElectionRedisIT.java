package com.smallrag.ragmcp.rag.sources;

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

    private LettuceConnectionFactory connectionFactory;
    private RedisDistributedLock lock;
    private SourcesLeaderElection first;
    private SourcesLeaderElection second;
    private DistributedProperties properties;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("127.0.0.1", PORT);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        lock = new RedisDistributedLock(redis);
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
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void leaseTakeoverAfterLeaderStopsRenewing() {
        first = new SourcesLeaderElection(lock, properties);
        second = new SourcesLeaderElection(lock, properties);

        first.tick();
        second.tick();
        assertThat(first.isLeader()).isTrue();
        assertThat(second.isLeader()).isFalse();

        sleep(2600);
        second.tick();
        assertThat(second.isLeader()).isTrue();

        // 旧 leader 在下一次续租时才会发现租约已丢失
        first.tick();
        assertThat(first.isLeader()).isFalse();
    }

    @Test
    void renewalKeepsLeadershipBeyondLease() {
        first = new SourcesLeaderElection(lock, properties);
        second = new SourcesLeaderElection(lock, properties);

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
