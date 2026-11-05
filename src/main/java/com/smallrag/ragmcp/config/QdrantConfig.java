package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.rag.KnowledgeBaseProbe;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CollectionParams;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 向量库配置。
 *
 * <p>设计参考 whale 的 QdrantConfig：
 * <ul>
 *   <li>集合不存在时启动期自动创建（Cosine + 配置维度）；</li>
 *   <li>Qdrant 不可用只告警，不阻断应用启动（fail-soft）；</li>
 *   <li>集合已存在但维度与配置不一致时 fail-fast——这是配置错误，
 *       带病启动只会让所有写入在运行期失败且难以定位。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RagProperties.class, QdrantProperties.class})
@Slf4j
public class QdrantConfig {

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(QdrantProperties properties) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient
                .newBuilder(properties.getHost(), properties.getPort(), properties.isUseTls())
                .withTimeout(Duration.ofMillis(properties.getInitTimeoutMs()));
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.withApiKey(properties.getApiKey());
        }
        log.info("Qdrant 客户端初始化完成: {}:{} (tls={})",
                properties.getHost(), properties.getPort(), properties.isUseTls());
        return new QdrantClient(builder.build());
    }

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public QdrantVectorStore vectorStore(QdrantClient qdrantClient,
                                         EmbeddingModel embeddingModel,
                                         QdrantProperties properties) {
        // initializeSchema=false：集合由下方 ApplicationRunner 幂等创建，
        // 避免 Qdrant 不可用时 Bean 初始化阶段直接抛异常阻断启动。
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(properties.getCollectionName())
                .contentFieldName("content")
                .initializeSchema(false)
                .build();
    }

    /**
     * 知识库为空时短路，避免为一次注定无结果的检索调用 Embedding 模型。
     */
    @Bean
    public KnowledgeBaseProbe knowledgeBaseProbe(QdrantClient qdrantClient, QdrantProperties properties) {
        return () -> {
            try {
                Long count = qdrantClient.countAsync(properties.getCollectionName())
                        .get(properties.getInitTimeoutMs(), TimeUnit.MILLISECONDS);
                return count != null && count == 0L;
            } catch (Exception e) {
                // 集合不存在 / Qdrant 暂时不可用：不短路，交给真实检索去暴露错误
                log.debug("Qdrant 探活失败，继续走检索: {}", e.toString());
                return false;
            }
        };
    }

    @Bean
    public SmartInitializingSingleton qdrantCollectionInitializer(QdrantClient qdrantClient,
            QdrantProperties properties) {
        return () -> {
            String collection = properties.getCollectionName();
            long timeout = properties.getInitTimeoutMs() + 1000L;
            try {
                boolean exists = Boolean.TRUE.equals(qdrantClient.collectionExistsAsync(collection)
                        .get(timeout, TimeUnit.MILLISECONDS));
                if (exists) {
                    verifyDimension(qdrantClient, properties);
                    return;
                }

                VectorParams vectorParams = VectorParams.newBuilder()
                        .setSize(properties.getDimension())
                        .setDistance(Distance.Cosine)
                        .build();
                qdrantClient.createCollectionAsync(collection, vectorParams)
                        .get(timeout, TimeUnit.MILLISECONDS);
                log.info("Qdrant 集合创建成功: {}, 维度={}, 距离=Cosine",
                        collection, properties.getDimension());
            } catch (DimensionMismatchException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Qdrant 集合初始化失败（fail-soft，不阻断启动）: collection={}, 原因={}",
                        collection, e.toString());
            }
        };
    }

    private void verifyDimension(QdrantClient qdrantClient, QdrantProperties properties) throws Exception {
        String collection = properties.getCollectionName();
        Integer actual = readVectorSize(qdrantClient, properties);
        log.info("Qdrant 集合已存在: {}, 配置维度={}, 集合维度={}",
                collection, properties.getDimension(), actual);
        if (actual == null) {
            log.warn("无法读取 Qdrant 集合 {} 的向量维度，跳过维度校验", collection);
            return;
        }
        if (actual != properties.getDimension()) {
            throw new DimensionMismatchException(String.format(
                    "Qdrant 集合维度与配置不一致：collection=%s, 集合维度=%d, rag.qdrant.dimension=%d。%n"
                            + "请让 rag.qdrant.dimension 与 Embedding 模型一致，"
                            + "或改用新集合名 / 重建集合并全量重建索引。",
                    collection, actual, properties.getDimension()));
        }
    }

    private Integer readVectorSize(QdrantClient qdrantClient, QdrantProperties properties) {
        try {
            CollectionInfo info = qdrantClient.getCollectionInfoAsync(properties.getCollectionName())
                    .get(properties.getInitTimeoutMs() + 1000L, TimeUnit.MILLISECONDS);
            if (!info.hasConfig()) {
                return null;
            }
            CollectionParams params = info.getConfig().getParams();
            if (!params.hasVectorsConfig()) {
                return null;
            }
            VectorsConfig vectorsConfig = params.getVectorsConfig();
            if (vectorsConfig.getConfigCase() != VectorsConfig.ConfigCase.PARAMS) {
                return null;
            }
            long size = vectorsConfig.getParams().getSize();
            return size <= 0L ? null : Math.toIntExact(size);
        } catch (Exception e) {
            log.debug("读取 Qdrant 集合 {} 向量维度失败: {}", properties.getCollectionName(), e.toString());
            return null;
        }
    }

    /**
     * 集合维度与配置不一致：配置错误，必须阻断启动。
     */
    static final class DimensionMismatchException extends IllegalStateException {
        DimensionMismatchException(String message) {
            super(message);
        }
    }
}
