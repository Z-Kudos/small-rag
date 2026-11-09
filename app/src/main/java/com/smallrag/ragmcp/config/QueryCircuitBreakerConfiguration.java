package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.config.RagProperties.QueryBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 检索熔断配置：把 {@code rag.query-breaker.*} 映射到 Spring Cloud CircuitBreaker
 * 的 Resilience4j 实现。
 *
 * <p>应用只依赖 Spring Cloud CircuitBreaker 抽象；Resilience4j 仅作为实现与诊断来源。
 * 线程池与 TimeLimiter 关闭，保持与旧实现一致：检索在调用线程执行，超时由
 * Embedding / Qdrant 自己的 HTTP / gRPC deadline 控制。
 *
 * @author zjk
 */
@Configuration(proxyBeanMethods = false)
public class QueryCircuitBreakerConfiguration {

    /**
     * 注册默认熔断配置；所有通过 {@code CircuitBreakerFactory#create} 创建的实例复用。
     *
     * @param properties RAG 配置
     * @return Resilience4j 工厂定制器
     */
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> ragQueryCircuitBreakerCustomizer(
            RagProperties properties) {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofMinutes(5))
                        .cancelRunningFuture(true)
                        .build())
                .circuitBreakerConfig(circuitBreakerConfig(properties.getQueryBreaker()))
                .build());
    }

    /**
     * 构造与旧 {@code rag.query-breaker.*} 语义一致的 CircuitBreaker 配置。
     *
     * @param properties 熔断参数
     * @return Resilience4j 熔断配置
     */
    public static CircuitBreakerConfig circuitBreakerConfig(QueryBreaker properties) {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(properties.effectiveSlidingWindowSize())
                .minimumNumberOfCalls(properties.effectiveMinimumNumberOfCalls())
                .failureRateThreshold(properties.getFailureRateThreshold())
                .waitDurationInOpenState(properties.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.effectivePermittedCalls())
                .slowCallDurationThreshold(properties.getSlowCallDurationThreshold())
                .slowCallRateThreshold(properties.getSlowCallRateThreshold())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }
}
