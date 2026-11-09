package com.smallrag.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * small-rag 的 Spring Cloud Gateway 入口。
 *
 * <p>通过 Consul 发现 {@code small-rag} 副本，使用 Spring Cloud LoadBalancer 做
 * 客户端负载均衡；MCP 端点是 {@code /mcp}，对外只暴露本服务的 18080。
 *
 * @author zjk
 */
@SpringBootApplication
public class GatewayApplication {

    /**
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
