package com.octopus.ragmcp.config;

import com.octopus.ragmcp.store.InMemoryVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储配置。
 *
 * <p>默认使用进程内实现，保证无外部依赖即可启动。切换到 Redis 时：
 * <ol>
 *   <li>移除 {@code application.yml} 中对
 *       {@code RedisVectorStoreAutoConfiguration} 的 exclude；</li>
 *   <li>设置 {@code rag.vector-store=redis}；</li>
 *   <li>配置 {@code spring.data.redis.*} 与 {@code spring.ai.vectorstore.redis.*}。</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
public class VectorStoreConfig {

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    @ConditionalOnProperty(name = "rag.vector-store", havingValue = "memory", matchIfMissing = true)
    public VectorStore inMemoryVectorStore(EmbeddingModel embeddingModel) {
        return new InMemoryVectorStore(embeddingModel);
    }
}
