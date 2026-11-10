package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.distributed.FencedLease;
import com.smallrag.ragmcp.distributed.LockHandle;
import com.smallrag.ragmcp.distributed.RedissonDistributedLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 的 Redisson RFencedLock 端到端测试。
 *
 * <p>默认不参与 {@code mvn test}；运行 {@code mvn verify -Pcontainer-it}。
 * 本地镜像可用 {@code -Dsmallrag.redis.image=redis:7.4-alpine} 覆盖。
 *
 * @author zjk
 */
class RedisLockContainerIT {

    private static final String KEY = "smallrag:test:lock";

    private static GenericContainer<?> redis;
    private static RedissonClient redisson;
    private static RedissonClient redissonA;
    private static RedissonClient redissonB;

    @BeforeAll
    static void startRedis() {
        String image = System.getProperty("smallrag.redis.image",
                System.getenv().getOrDefault("SMALL_RAG_REDIS_IMAGE", "redis:7.4-alpine"));
        redis = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        redis.start();
        Config config = new Config();
        config.useSingleServer().setAddress(
                "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379))
                .setDatabase(15);
        config.setLockWatchdogTimeout(500);
        redisson = org.redisson.Redisson.create(config);
        redissonA = org.redisson.Redisson.create(config);
        redissonB = org.redisson.Redisson.create(config);
    }

    @AfterAll
    static void stopRedis() {
        shutdownQuietly(redissonA);
        shutdownQuietly(redissonB);
        shutdownQuietly(redisson);
        if (redis != null) {
            redis.stop();
        }
    }

    private static void shutdownQuietly(RedissonClient client) {
        if (client != null) {
            client.shutdown();
        }
    }

    @BeforeEach
    void cleanKeys() {
        redisson.getKeys().flushdb();
    }

    @AfterEach
    void tearDown() {
        cleanKeys();
    }

    @Test
    void onlyOneReplicaCanHoldAndFencingTokenIsMonotonic() {
        RedissonDistributedLock first = new RedissonDistributedLock(redissonA);
        RedissonDistributedLock second = new RedissonDistributedLock(redissonB);

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
    void lockLossAllowsTakeoverAndOldLeaseIsRejected() {
        RedissonDistributedLock first = new RedissonDistributedLock(redissonA);
        RedissonDistributedLock second = new RedissonDistributedLock(redissonB);
        FencedLease leaseA = FencedLease.acquire(first, KEY, "replica-a", Duration.ofSeconds(30))
                .orElseThrow();

        // 模拟锁因进程崩溃/租约过期而消失（watchdog 停止续租后 TTL 到期）
        redisson.getKeys().delete(KEY);

        FencedLease leaseB = FencedLease.acquire(second, KEY, "replica-b", Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(leaseB.epoch()).isEqualTo(2L);
        assertThat(leaseA.isValid()).isFalse();
        assertThat(leaseB.isValid()).isTrue();
    }

    @Test
    void renewOnlyWorksForCurrentHolder() {
        RedissonDistributedLock first = new RedissonDistributedLock(redissonA);
        RedissonDistributedLock second = new RedissonDistributedLock(redissonB);
        LockHandle handle = first.tryAcquire(KEY, "replica-a", Duration.ofSeconds(30)).orElseThrow();

        assertThat(first.renew(handle, Duration.ofSeconds(30))).isTrue();
        assertThat(second.isHeld(handle)).isTrue();
        first.release(handle);
        assertThat(second.isHeld(handle)).isFalse();

        Optional<LockHandle> other = second.tryAcquire(KEY, "replica-b", Duration.ofSeconds(30));
        assertThat(other).isPresent();
        assertThat(first.renew(handle, Duration.ofSeconds(30))).isFalse();
    }

    @Test
    void forceUnlockDoesNotAffectNewerHolder() {
        RedissonDistributedLock first = new RedissonDistributedLock(redissonA);
        RedissonDistributedLock second = new RedissonDistributedLock(redissonB);
        LockHandle handleA = first.tryAcquire(KEY, "replica-a", Duration.ofSeconds(30)).orElseThrow();

        // 锁过期后由 B 接管；A 的延迟 forceUnlock 不能删除 B 的锁
        redisson.getKeys().delete(KEY);
        LockHandle handleB = second.tryAcquire(KEY, "replica-b", Duration.ofSeconds(30)).orElseThrow();

        first.release(handleA);

        assertThat(second.isHeld(handleB)).isTrue();
        assertThat(second.currentOwner(KEY)).contains("replica-b");
    }
}
