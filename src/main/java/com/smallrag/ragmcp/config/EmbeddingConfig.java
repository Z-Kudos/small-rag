package com.smallrag.ragmcp.config;

import com.openai.core.Timeout;
import com.smallrag.ragmcp.rag.CancellationScope;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Embedding（OpenAI 兼容）HTTP 调用：超时 + 任务级主动取消。
 *
 * <p>Spring AI 2.x 使用 OpenAI 官方 SDK + OkHttp，{@code spring.http.client.*} 不生效；
 * 通过 {@link OpenAiHttpClientBuilderCustomizer} 设置 connect/read/write/request 超时，
 * 避免 Embedding 服务假死时重建线程被永久占用；同时注册
 * {@link CancellationScope#interceptor()}，让看门狗硬超时能直接取消在途调用。
 *
 * @author zjk
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingConfig {

    @Bean
    public OpenAiHttpClientBuilderCustomizer embeddingHttpTimeoutCustomizer(RagProperties properties) {
        Duration connect = Duration.ofMillis(Math.max(1_000, properties.getEmbedding().getConnectTimeoutMs()));
        Duration read = Duration.ofMillis(Math.max(1_000, properties.getEmbedding().getReadTimeoutMs()));
        Timeout timeout = Timeout.builder()
                .connect(connect)
                .read(read)
                .write(read)
                .request(read.plus(connect))
                .build();
        return builder -> builder.timeout(timeout).interceptor(CancellationScope.interceptor());
    }
}
