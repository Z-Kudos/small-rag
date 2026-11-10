package com.smallrag.ragmcp.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单实例索引写入活动的互斥标记。
 *
 * <p>重建任务（{@code reindex:<taskId>}）与目录导入（{@code sources}）都会写入
 * Qdrant 与归档；两者并发会互相删除/覆盖对方刚写入的片段，因此用同一个
 * 进程内 owner 标记串行化。单实例部署不需要租约、续租或跨进程锁。
 *
 * @author zjk
 */
@Component
@Slf4j
public class IndexActivityGuard {

    /**
     * 目录导入活动标识。
     */
    public static final String SOURCES = "sources";

    private final AtomicReference<String> owner = new AtomicReference<>();

    /**
     * 重建任务活动标识。
     *
     * @param taskId 任务 id
     * @return 活动标识
     */
    public static String reindex(String taskId) {
        return "reindex:" + taskId;
    }

    /**
     * 尝试获取活动锁。
     *
     * @param label 活动标识，例如 {@code reindex:<taskId>} 或 {@code sources}
     * @return true 表示获取成功
     */
    public boolean tryAcquire(String label) {
        if (label == null || label.isBlank()) {
            return false;
        }
        if (!owner.compareAndSet(null, label)) {
            log.debug("索引活动锁已被占用: current={}, requested={}", owner.get(), label);
            return false;
        }
        return true;
    }

    /**
     * 释放活动锁；只有当前持有者匹配时才释放。
     *
     * @param label 活动标识
     */
    public void release(String label) {
        if (label == null) {
            return;
        }
        while (true) {
            String current = owner.get();
            if (!Objects.equals(current, label)) {
                log.debug("忽略非持有者的释放请求: current={}, requested={}", current, label);
                return;
            }
            if (owner.compareAndSet(current, null)) {
                return;
            }
        }
    }

    /**
     * 当前实例是否持有该活动锁。
     *
     * @param label 活动标识
     * @return true 表示持有
     */
    public boolean isHeldBy(String label) {
        return Objects.equals(owner.get(), label);
    }

    /**
     * @return 当前活动标识
     */
    public Optional<String> owner() {
        return Optional.ofNullable(owner.get());
    }

    /**
     * 当前活动锁的持有者（单实例下与 {@link #owner()} 相同）。
     *
     * @return 持有者标识；无锁时为空
     */
    public Optional<String> activeOwner() {
        return owner();
    }
}
