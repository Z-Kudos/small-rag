package com.smallrag.ragmcp.rag.vectorstore;

/**
 * 全量重建后孤儿片段的清理结果。
 *
 * @param parentPoints 扫描到的父块 point 数量
 * @param orphanParents 被判定为孤儿（没有对应子块）并删除的父块数量
 * @author zjk
 */
public record CleanupResult(int parentPoints, int orphanParents) {
}
