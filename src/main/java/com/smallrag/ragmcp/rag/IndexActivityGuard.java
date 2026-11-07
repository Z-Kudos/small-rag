package com.smallrag.ragmcp.rag;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 索引写入活动的进程内互斥标记。
 *
 * <p>重建任务（{@code reindex:<taskId>}）与目录导入（{@code sources}）都会写入
 * Qdrant 与归档；两者并发会互相删除/覆盖对方刚写入的片段，因此用同一把活动锁
 * 串行化。它不是分布式锁，多副本部署仍按单实例设计。
 *
 * @author zjk
 */
@Component
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
    public synchronized boolean tryAcquire(String label) {
        if (label == null || owner.get() != null) {
            return false;
        }
        owner.set(label);
        return true;
    }

    /**
     * 释放活动锁；只有当前持有者匹配时才释放。
     *
     * <p>按内容比较：调用方每次拼接出来的 label 可能是不同的 String 实例。
     *
     * @param label 活动标识
     */
    public synchronized void release(String label) {
        if (Objects.equals(owner.get(), label)) {
            owner.set(null);
        }
    }

    /**
     * @return 当前活动标识
     */
    public Optional<String> owner() {
        return Optional.ofNullable(owner.get());
    }
}
