package com.smallrag.ragmcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 归档卷部署信息，对应 {@code smallrag.archive.*}。
 *
 * <p>distributed 多副本必须共享同一个归档目录（RWX），否则各副本看到的历史文档
 * 不一致，自动重切与任务续接会互相覆盖。该开关只用于启动期校验与健康检查，
 * 不改变归档读写行为。
 *
 * @author zjk
 */
@Data
@ConfigurationProperties(prefix = "smallrag.archive")
public class ArchiveProperties {

    /**
     * 归档目录是否由多副本共享（distributed 部署应设为 true）。
     */
    private boolean shared;
}
