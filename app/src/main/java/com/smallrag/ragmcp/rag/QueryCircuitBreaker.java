package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.QueryCircuitBreakerConfiguration;
import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.config.RagProperties.QueryBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 检索路径熔断器，基于 Spring Cloud CircuitBreaker API。
 *
 * <p>只包住 {@code rag_search} / {@code rag_query} 的检索调用（Embedding + Qdrant）。
 * 连续失败或慢调用达到阈值后打开熔断，后续请求快速失败，避免线程被慢调用占满；
 * 入库与重建不经过这里，仍按原有的重试/续接策略处理。
 *
 * <p>底层实现由 {@code spring-cloud-starter-circuitbreaker-resilience4j} 提供；
 * 应用代码只依赖 {@link CircuitBreakerFactory}，状态与失败率通过实现侧的 registry
 * 暴露给健康检查。关闭时退化为直接调用，保持 standalone 的简单行为。
 *
 * @author zjk
 */
@Component
@Slf4j
public class QueryCircuitBreaker {

    private static final String NAME = "ragQuery";

    private final boolean enabled;
    private final CircuitBreaker breaker;
    private final io.github.resilience4j.circuitbreaker.CircuitBreaker delegate;

    /**
     * Spring 注入构造器。
     *
     * @param factory    Spring Cloud CircuitBreaker 工厂
     * @param properties RAG 配置
     */
    @Autowired
    public QueryCircuitBreaker(CircuitBreakerFactory<?, ?> factory, RagProperties properties) {
        this(factory, properties.getQueryBreaker());
    }

    /**
     * 测试 / 非 Spring 场景入口：使用 Resilience4j 实现构建默认工厂。
     *
     * @param properties RAG 配置
     */
    public QueryCircuitBreaker(RagProperties properties) {
        this(defaultFactory(properties.getQueryBreaker()), properties.getQueryBreaker());
    }

    /**
     * 测试入口：直接指定熔断参数。
     *
     * @param properties 熔断配置
     */
    QueryCircuitBreaker(QueryBreaker properties) {
        this(defaultFactory(properties), properties);
    }

    private QueryCircuitBreaker(CircuitBreakerFactory<?, ?> factory, QueryBreaker properties) {
        this.enabled = properties.isEnabled();
        if (!enabled) {
            this.breaker = null;
            this.delegate = null;
            return;
        }
        this.breaker = factory.create(NAME);
        CircuitBreakerRegistry registry = factory instanceof Resilience4JCircuitBreakerFactory resilience4j
                ? resilience4j.getCircuitBreakerRegistry()
                : null;
        this.delegate = registry == null
                ? null
                : registry.circuitBreaker(NAME,
                        QueryCircuitBreakerConfiguration.circuitBreakerConfig(properties));
        if (delegate != null) {
            delegate.getEventPublisher().onStateTransition(event -> log.warn(
                    "检索熔断状态变化: {} -> {}, 失败率={}%",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState(),
                    delegate.getMetrics().getFailureRate()));
        }
    }

    /**
     * 执行一次受保护的检索调用。
     *
     * @param supplier 检索逻辑
     * @param <T>      返回类型
     * @return 检索结果
     * @throws QueryUnavailableException 熔断已打开，本次调用被拒绝
     */
    public <T> T execute(Supplier<T> supplier) {
        if (!enabled || breaker == null) {
            return supplier.get();
        }
        return breaker.run(supplier, throwable -> {
            if (throwable instanceof CallNotPermittedException) {
                throw new QueryUnavailableException(
                        "知识库检索暂时不可用（熔断已打开，请稍后重试）", throwable);
            }
            throw propagate(throwable);
        });
    }

    /**
     * @return 熔断是否打开（诊断用）
     */
    public boolean isOpen() {
        return delegate != null
                && delegate.getState() == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
    }

    /**
     * @return 当前失败率百分比；无统计数据或未启用时为 -1
     */
    public float failureRate() {
        return delegate == null ? -1f : delegate.getMetrics().getFailureRate();
    }

    /**
     * @return 当前熔断状态；未启用时为 {@code DISABLED}，无实现侧状态时为 {@code UNKNOWN}
     */
    public String state() {
        if (breaker == null) {
            return "DISABLED";
        }
        return delegate == null ? "UNKNOWN" : delegate.getState().name();
    }

    private static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException runtime) {
            return runtime;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(throwable);
    }

    /**
     * 非 Spring 场景的默认工厂：关闭线程池与 TimeLimiter，保持调用线程语义。
     */
    private static Resilience4JCircuitBreakerFactory defaultFactory(QueryBreaker properties) {
        Resilience4JConfigurationProperties configuration = new Resilience4JConfigurationProperties();
        configuration.setDisableThreadPool(true);
        configuration.setDisableTimeLimiter(true);
        Resilience4JCircuitBreakerFactory factory = new Resilience4JCircuitBreakerFactory(
                CircuitBreakerRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(),
                null, configuration);
        factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofMinutes(5))
                        .build())
                .circuitBreakerConfig(QueryCircuitBreakerConfiguration.circuitBreakerConfig(properties))
                .build());
        return factory;
    }
}
