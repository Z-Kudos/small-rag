package com.smallrag.ragmcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 两个向量库后端共享的配置：RagProperties 与 QdrantProperties
 * 始终注册，避免 RagService 等只关心命名空间的组件绑定到具体后端。
 *
 * @author zjk
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RagProperties.class, QdrantProperties.class})
public class CommonRagConfig {
}
