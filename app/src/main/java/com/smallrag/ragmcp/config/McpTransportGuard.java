package com.smallrag.ragmcp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动期校验 MCP 传输协议与运行形态是否匹配。
 *
 * <p>standalone 只有单个副本，{@code STREAMABLE} 的会话状态没有路由问题；distributed
 * 多副本由 gateway 轮询，会话只存在单副本内存，必须使用 {@code STATELESS}。这里只做
 * 告警，不改变配置，避免隐藏用户的显式选择。
 *
 * @author zjk
 */
@Component
@Slf4j
public class McpTransportGuard {

    private static final String PROTOCOL_PROPERTY = "spring.ai.mcp.server.protocol";
    private static final String STATELESS = "STATELESS";

    /**
     * @param environment  Spring 环境，读取 MCP 传输协议
     * @param distributed 分布式配置，判断是否多副本
     */
    public McpTransportGuard(Environment environment, DistributedProperties distributed) {
        String protocol = environment.getProperty(PROTOCOL_PROPERTY, "STREAMABLE");
        if (distributed.isEnabled() && !STATELESS.equalsIgnoreCase(protocol)) {
            log.warn("distributed 模式应使用无状态 MCP 传输：当前 {}={}，多副本轮询会丢失会话并返回 404；"
                    + "请设置 SMALL_RAG_MCP_PROTOCOL=STATELESS", PROTOCOL_PROPERTY, protocol);
            return;
        }
        log.info("MCP 传输协议: {}, distributed={}", protocol, distributed.isEnabled());
    }
}
