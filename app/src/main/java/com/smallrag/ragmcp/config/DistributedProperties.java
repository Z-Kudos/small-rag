package com.smallrag.ragmcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.time.Duration;

/**
 * 分布式模式配置，对应 {@code smallrag.distributed.*}。
 *
 * <p>默认关闭：standalone 单容器模式不依赖 Redis / Consul，行为与 v0.0.1 一致。
 * 打开后由 {@code distributed} profile 与 compose overlay 提供外部依赖。
 *
 * @author zjk
 */
@Data
@ConfigurationProperties(prefix = "smallrag.distributed")
public class DistributedProperties {

    /**
     * 是否启用分布式模式（Redis 租约锁、Consul 等）。
     */
    private boolean enabled;

    /**
     * 当前实例标识，用于日志与任务归属；默认 hostname-pid。
     */
    private String instanceId = defaultInstanceId();

    /**
     * 跨副本任务状态读穿缓存时长：本地内存缺失时，同一任务在此时长内只回读一次 Qdrant。
     */
    private Duration taskStatusCacheMs = Duration.ofMillis(2000);

    /**
     * 分布式锁参数。
     */
    private Lock lock = new Lock();

    /**
     * 锁租约与续租配置。
     */
    @Data
    public static class Lock {

        /**
         * 租约时长；实例失联后其他副本需等待该时长才能接管。
         */
        private Duration lease = Duration.ofSeconds(30);

        /**
         * 续租间隔；必须明显小于租约时长。
         */
        private Duration renewInterval = Duration.ofSeconds(10);

        /**
         * Redis key 前缀。
         */
        private String keyPrefix = "smallrag:lock:";
    }

    private static String defaultInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + "-" + ProcessHandle.current().pid();
    }
}
