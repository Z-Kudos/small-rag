package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.distributed.FencedLease;
import com.smallrag.ragmcp.distributed.LockHandle;
import com.smallrag.ragmcp.distributed.RedisDistributedLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 的分布式锁 / fence epoch 端到端测试。
 *
 * <p>默认不参与 {@code mvn test}；运行 {@code mvn verify -Pcontainer-it}。
 * 本地镜像可用 {@code -Dsmallrag.redis.image=redis:7.4-alpine} 覆盖。
 *
 * @author zjk
 */
class RedisLockContainerIT {

    private static final String KEY = "smallrag:test:lock";

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void startRedis() {
        String image = System.getProperty("smallrag.redis.image",
                System.getenv().getOrDefault("SMALL_RAG_REDIS_IMAGE", "redis:7.4-alpine"));
        redis = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        redis.start();
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                redis.getHost(), redis.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redis != null) {
            redis.stop();
        }
    }

    @BeforeEach
    void cleanKeys() {
        template.delete(KEY);
        template.delete(KEY + ":epoch");
    }

    @AfterEach
    void tearDown() {
        cleanKeys();
    }

    @Test
    void onlyOneReplicaCanHoldAndEpochIsMonotonic() {
        RedisDistributedLock first = new RedisDistributedLock(template);
        RedisDistributedLock second = new RedisDistributedLock(template);

        FencedLease leaseA = FencedLease.acquire(first, KEY, "replica-a", Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(FencedLease.acquire(second, KEY, "replica-b", Duration.ofSeconds(30))).isEmpty();
        assertThat(leaseA.epoch()).isEqualTo(1L);

        leaseA.release();
        FencedLease leaseB = FencedLease.acquire(second, KEY, "replica-b", Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(leaseB.epoch()).isEqualTo(2L);
        // 旧持有者恢复后不能再写入
        assertThat(leaseA.isValid()).isFalse();
        assertThat(leaseB.isValid()).isTrue();
    }

    @Test
    void expiredHolderIsTakenOverAndOldLeaseIsRejected() throws Exception {
        RedisDistributedLock first = new RedisDistributedLock(template);
        RedisDistributedLock second = new RedisDistributedLock(template);
        FencedLease leaseA = FencedLease.acquire(first, KEY, "replica-a", Duration.ofMillis(300))
                .orElseThrow();

        Thread.sleep(450);

        FencedLease leaseB = FencedLease.acquire(second, KEY, "replica-b", Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(leaseB.epoch()).isEqualTo(2L);
        assertThat(leaseA.isValid()).isFalse();
        assertThat(leaseB.isValid()).isTrue();
    }

    @Test
    void renewOnlyWorksForCurrentHolder() {
        RedisDistributedLock first = new RedisDistributedLock(template);
        RedisDistributedLock second = new RedisDistributedLock(template);
        LockHandle handle = first.tryAcquire(KEY, "replica-a", Duration.ofSeconds(30)).orElseThrow();

        assertThat(first.renew(handle, Duration.ofSeconds(30))).isTrue();
        assertThat(second.isHeld(handle)).isTrue();
        first.release(handle);
        assertThat(second.isHeld(handle)).isFalse();

        Optional<LockHandle> other = second.tryAcquire(KEY, "replica-b", Duration.ofSeconds(30));
        assertThat(other).isPresent();
        assertThat(first.renew(handle, Duration.ofSeconds(30))).isFalse();
    }
}
