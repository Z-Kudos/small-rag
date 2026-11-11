package com.smallrag.ragmcp.rag.vectorstore;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.rag.chunking.ChunkingService;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.FieldCondition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Match;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.VectorsOutput;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import com.smallrag.ragmcp.rag.RagService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 实现的 {@link DocumentPointsPort}：直接使用 gRPC 客户端做
 * 枚举、删除、按 id 取回父块与孤儿片段清理。
 *
 * <p>这些操作 {@code VectorStore} 接口表达不了，故保留原生路径；
 * 切换为 SQLite 后端时由对应实现替代，对 {@code RagService} 无感知。
 *
 * @author zjk
 */
@Slf4j
public class QdrantDocumentPointsPort implements DocumentPointsPort {

    private final QdrantClient qdrantClient;
    private final QdrantProperties properties;

    public QdrantDocumentPointsPort(QdrantClient qdrantClient, QdrantProperties properties) {
        this.qdrantClient = qdrantClient;
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Set<String>> findPointIds(String docId) {
        long timeout = Math.max(1L, properties.getRequestTimeoutMs());
        try {
            Set<String> ids = new HashSet<>();
            PointId offset = null;
            do {
                ScrollPoints.Builder request = ScrollPoints.newBuilder()
                        .setCollectionName(properties.getCollectionName())
                        .setFilter(Filter.newBuilder()
                                .addMust(Condition.newBuilder()
                                        .setField(FieldCondition.newBuilder()
                                                .setKey("doc_id")
                                                .setMatch(Match.newBuilder().setKeyword(docId))))
                                .build())
                        .setLimit(256)
                        .setWithPayload(WithPayloadSelector.newBuilder().setEnable(false).build());
                if (offset != null) {
                    request.setOffset(offset);
                }
                ScrollResponse response = qdrantClient.scrollAsync(request.build())
                        .get(timeout, TimeUnit.MILLISECONDS);
                for (RetrievedPoint point : response.getResultList()) {
                    ids.add(point.getId().getUuid());
                }
                offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
            } while (offset != null);
            return Optional.of(ids);
        } catch (Exception e) {
            log.debug("读取文档旧片段 point id 失败，回退为先删后写: docId={}, 原因={}",
                    docId, e.toString());
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deletePoints(Set<String> pointIds) throws IllegalStateException {
        long timeout = Math.max(1L, properties.getRequestTimeoutMs());
        List<PointId> ids = pointIds.stream()
                .map(id -> PointIdFactory.id(UUID.fromString(id)))
                .toList();
        try {
            qdrantClient.deleteAsync(properties.getCollectionName(), ids)
                    .get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("删除旧片段失败: " + ids.size(), e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public CleanupResult cleanupOrphans() throws IllegalStateException {
        String collection = properties.getCollectionName();
        long timeout = Math.max(1L, properties.getRequestTimeoutMs());

        // 旧版本片段：有 doc_id 但没有 chunk_type，无法被 rag_rebuild 的类型过滤命中
        Filter legacy = Filter.newBuilder()
                .addMust(Condition.newBuilder().setIsEmpty(
                        io.qdrant.client.grpc.Common.IsEmptyCondition.newBuilder()
                                .setKey(ChunkingService.FIELD_CHUNK_TYPE)))
                .addMustNot(Condition.newBuilder().setIsEmpty(
                        io.qdrant.client.grpc.Common.IsEmptyCondition.newBuilder()
                                .setKey("doc_id")))
                .build();
        try {
            qdrantClient.deleteAsync(collection, legacy).get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("清理旧版本片段失败: " + collection, e);
        }

        Map<UUID, String> parents = new LinkedHashMap<>();
        Set<String> liveParentIds = new HashSet<>();
        Filter typed = Filter.newBuilder()
                .addShould(Condition.newBuilder().setField(FieldCondition.newBuilder()
                        .setKey(ChunkingService.FIELD_CHUNK_TYPE)
                        .setMatch(Match.newBuilder().setKeyword(ChunkingService.CHUNK_TYPE_PARENT))))
                .addShould(Condition.newBuilder().setField(FieldCondition.newBuilder()
                        .setKey(ChunkingService.FIELD_CHUNK_TYPE)
                        .setMatch(Match.newBuilder().setKeyword(ChunkingService.CHUNK_TYPE_CHILD))))
                .addShould(Condition.newBuilder().setField(FieldCondition.newBuilder()
                        .setKey(ChunkingService.FIELD_CHUNK_TYPE)
                        .setMatch(Match.newBuilder().setKeyword(ChunkingService.CHUNK_TYPE_CHUNK))))
                .build();
        WithPayloadSelector include = WithPayloadSelector.newBuilder()
                .setInclude(io.qdrant.client.grpc.Points.PayloadIncludeSelector.newBuilder()
                        .addFields(ChunkingService.FIELD_CHUNK_TYPE)
                        .addFields(ChunkingService.FIELD_PARENT_ID))
                .build();
        PointId offset = null;
        do {
            ScrollPoints.Builder request = ScrollPoints.newBuilder()
                    .setCollectionName(collection)
                    .setFilter(typed)
                    .setLimit(256)
                    .setWithPayload(include);
            if (offset != null) {
                request.setOffset(offset);
            }
            ScrollResponse response;
            try {
                response = qdrantClient.scrollAsync(request.build())
                        .get(timeout, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("扫描孤儿父块失败: " + collection, e);
            }
            for (RetrievedPoint point : response.getResultList()) {
                Map<String, Value> payload = point.getPayloadMap();
                String type = valueOf(payload.get(ChunkingService.FIELD_CHUNK_TYPE));
                String parentId = valueOf(payload.get(ChunkingService.FIELD_PARENT_ID));
                if (ChunkingService.CHUNK_TYPE_PARENT.equals(type)) {
                    parents.put(UUID.fromString(point.getId().getUuid()), parentId);
                } else if (parentId != null) {
                    liveParentIds.add(parentId);
                }
            }
            offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
        } while (offset != null);

        List<PointId> orphans = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : parents.entrySet()) {
            String parentId = entry.getValue();
            if (parentId == null || parentId.isBlank() || !liveParentIds.contains(parentId)) {
                orphans.add(PointIdFactory.id(entry.getKey()));
            }
        }
        if (!orphans.isEmpty()) {
            try {
                qdrantClient.deleteAsync(collection, orphans).get(timeout, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("删除孤儿父块失败: " + orphans.size(), e);
            }
        }
        log.info("孤儿片段清理完成: legacyFilter=true, parents={}, orphanParents={}",
                parents.size(), orphans.size());
        return new CleanupResult(parents.size(), orphans.size());
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, ParentPoint> fetchParents(Set<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> uuidToParent = new HashMap<>();
        List<PointId> pointIds = new ArrayList<>(parentIds.size());
        for (String parentId : parentIds) {
            UUID uuid = PointIds.parentPointUuid(parentId);
            uuidToParent.put(uuid, parentId);
            pointIds.add(PointIdFactory.id(uuid));
        }
        try {
            List<RetrievedPoint> points = qdrantClient
                    .retrieveAsync(properties.getCollectionName(), pointIds, true, true, null)
                    .get(properties.getInitTimeoutMs(), TimeUnit.MILLISECONDS);
            Map<String, ParentPoint> result = new HashMap<>();
            for (RetrievedPoint point : points) {
                UUID uuid = UUID.fromString(point.getId().getUuid());
                String parentId = uuidToParent.get(uuid);
                if (parentId == null) {
                    continue;
                }
                Value content = point.getPayloadMap().get(RagService.CONTENT_FIELD);
                if (content == null || content.getStringValue().isBlank()) {
                    continue;
                }
                result.put(parentId, new ParentPoint(parentId, point.getId().getUuid(),
                        content.getStringValue(), extractVector(point.getVectors())));
            }
            return result;
        } catch (Exception e) {
            log.warn("父块独立 point 取回失败，回退为子块结果: {}", e.toString());
            return Map.of();
        }
    }

    private static String valueOf(Value value) {
        return value == null || value.getStringValue().isBlank() ? null : value.getStringValue();
    }

    /**
     * 从 RetrievedPoint 提取稠密向量；无向量或非稠密时返回 null（MMR 退化为纯相关排序）。
     */
    private static float[] extractVector(VectorsOutput vectors) {
        if (vectors == null || !vectors.hasVector()) {
            return null;
        }
        List<Float> data = vectors.getVector().hasDense()
                ? vectors.getVector().getDense().getDataList()
                : vectors.getVector().getDataList();
        if (data == null || data.isEmpty()) {
            return null;
        }
        float[] result = new float[data.size()];
        for (int i = 0; i < data.size(); i++) {
            result[i] = data.get(i);
        }
        return result;
    }
}
