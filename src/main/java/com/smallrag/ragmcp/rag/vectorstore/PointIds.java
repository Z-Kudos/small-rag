package com.smallrag.ragmcp.rag.vectorstore;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * point id 的确定性映射：同一文档在相同切分配置下重复写入会覆盖同一批 point，
 * 不再依赖“先写后删旧”。
 *
 * @author zjk
 */
public final class PointIds {

    private PointIds() {
    }

    /**
     * parent_id → 父块 point id 的确定性映射，重切后保持稳定。
     *
     * @param parentId 父块业务 id
     * @return 稳定的 point UUID
     */
    public static UUID parentPointUuid(String parentId) {
        return UUID.nameUUIDFromBytes(("parent:" + parentId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 子块 point id：由文档 id、切分配置签名与块序号确定性生成。
     *
     * <p>同一文档在相同切分配置下重复写入会覆盖同一批 point，不再依赖“先写后删旧”；
     * 切分配置变化时签名变化，新旧 id 不同，仍由 {@code reindex} 写入后删除旧片段。
     *
     * @param documentId 文档 id
     * @param signature  切分配置签名
     * @param index      块序号
     * @return 稳定的 point UUID
     */
    public static UUID chunkPointUuid(String documentId, String signature, int index) {
        return UUID.nameUUIDFromBytes(("chunk:" + documentId + ":" + signature + ":" + index)
                .getBytes(StandardCharsets.UTF_8));
    }
}
