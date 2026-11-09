package com.smallrag.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * small-rag 的 Spring Cloud Config Server（native 文件系统后端）。
 *
 * <p>配置仓库由 {@code CONFIG_SERVER_SEARCH_LOCATIONS} 指定（默认 {@code file:/config}），
 * 容器部署时把 {@code config-repo/} 只读挂载到该目录。应用通过
 * {@code SPRING_CONFIG_IMPORT=optional:configserver:http://config-server:8888} 拉取。
 *
 * @author zjk
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    /**
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
