package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.mcp.RagMcpTools;
import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.ReindexTaskService;
import com.smallrag.ragmcp.rag.sources.SourceSyncService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP Server 显式配置：集中注册暴露给 Agent 的工具。
 *
 * <p>服务端 name / version / protocol / instructions 通过
 * {@code spring.ai.mcp.server.*} 配置项设置（见 application.yml）。
 * 不使用 {@code McpSyncServerCustomizer}，因为该扩展点为单例，
 * 已由 WebMVC 传输的自动配置占用。
 *
 * @author zjk
 */
@Configuration(proxyBeanMethods = false)
public class McpServerConfig {

    @Bean
    public RagMcpTools ragMcpTools(RagService ragService,
            ReindexTaskService reindexTaskService,
            SourceSyncService sourceSyncService,
            ObjectMapper objectMapper) {
        return new RagMcpTools(ragService, reindexTaskService, sourceSyncService, objectMapper);
    }
}
