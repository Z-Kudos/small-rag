package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.RagProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 检索路径熔断器（单机版，直接使用 Resilience4j）。
 *
 * <p>只包住 {@code rag_search} / {@code rag_query} 的检索调用（Embedding + Qdrant）。
 * 连续失败或慢调用达到阈值后打开熔断，后续请求快速失败，避免线程被慢调用占满；
 * 入库与重建不经过这里，仍按原有的重试/续接策略处理。
 *
 * <p>关闭时退化为直接调用，保持单机部署的简单行为。
 *
 * @author zjk
 */
@Component
@Slf4j
public class QueryCircuitBreaker {

    private static final String NAME = "ragQuery";

    private final boolean enabled;
    private final CircuitBreaker breaker;

    /**
     * @param properties RAG 配置（读取 {@code rag.query-breaker.*}）
     */
    @Autowired
    public QueryCircuitBreaker(RagProperties properties) {
        this(properties.getQueryBreaker());
    }

    /**
     * 测试入口。
     *
     * @param properties 熔断配置
     */
    QueryCircuitBreaker(RagProperties.QueryBreaker properties) {
        this.enabled = properties.isEnabled();
        if (!enabled) {
            this.breaker = null;
            return;
        }
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(properties.effectiveSlidingWindowSize())
                .minimumNumberOfCalls(properties.effectiveMinimumNumberOfCalls())
                .failureRateThreshold(properties.getFailureRateThreshold())
                .waitDurationInOpenState(properties.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.effectivePermittedCalls())
                .slowCallDurationThreshold(properties.getSlowCallDurationThreshold())
                .slowCallRateThreshold(properties.getSlowCallRateThreshold())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        this.breaker = CircuitBreaker.of(NAME, config);
        breaker.getEventPublisher().onStateTransition(event -> log.warn(
                "检索熔断状态变化: {} -> {}, 失败率={}%",
                event.getStateTransition().getFromState(),
                event.getStateTransition().getToState(),
                breaker.getMetrics().getFailureRate()));
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
        try {
            return breaker.executeSupplier(supplier);
        } catch (CallNotPermittedException e) {
            throw new QueryUnavailableException(
                    "知识库检索暂时不可用（熔断已打开，请稍后重试）", e);
        }
    }

    /**
     * @return 熔断是否打开（诊断用）
     */
    public boolean isOpen() {
        return breaker != null && breaker.getState() == CircuitBreaker.State.OPEN;
    }

    /**
     * @return 当前失败率百分比；无统计数据或未启用时为 -1
     */
    public float failureRate() {
        if (breaker == null) {
            return -1f;
        }
        return breaker.getMetrics().getFailureRate();
    }

    /**
     * @return 当前熔断状态；未启用时为 {@code DISABLED}
     */
    public String state() {
        return breaker == null ? "DISABLED" : breaker.getState().name();
    }
}
