package com.smallrag.ragmcp.rag.reindex;

import com.smallrag.ragmcp.config.QdrantProperties;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.FieldCondition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Match;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把重建任务状态写入 Qdrant 的独立集合。
 *
 * <p>集合名为 {@code <rag.qdrant.collection-name>_reindex_state}，与向量数据在同一个
 * Qdrant 实例/卷上，因此跟随 Qdrant 的持久化，不依赖应用容器的文件系统。任务快照以
 * JSON 字符串放在 payload 中；单实例下不保存任何锁状态。
 *
 * <p>Qdrant 的 collection 必须配置向量，这里用一个 1 维、全零的哑向量（Distance.Dot），
 * 只为满足约束，不参与检索。状态集合与主集合隔离，不会被 {@code rag_rebuild} 清理，
 * 也不会影响空库探活/检索结果。
 *
 * <p>读写失败直接抛出运行时异常，由 {@link ReindexStateStore} 决定是否降级；
 * 集合按需创建，Qdrant 暂时不可用不会永久禁用本次进程，后续调用会重试。
 *
 * @author zjk
 */
@Slf4j
final class QdrantReindexStateBackend implements ReindexStateBackend {

    private static final String COLLECTION_SUFFIX = "_reindex_state";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_PAYLOAD = "payload";
    private static final String KIND_TASK = "task";
    private static final String TASK_ID_PREFIX = "small-rag-reindex-task:";
    private static final int PAGE_SIZE = 128;

    private final QdrantClient client;
    private final ObjectMapper objectMapper;
    private final String collection;
    private final long timeoutMs;
    private final AtomicBoolean collectionReady = new AtomicBoolean();

    QdrantReindexStateBackend(ObjectMapper objectMapper, QdrantProperties properties,
            QdrantClient client) {
        this.objectMapper = objectMapper;
        this.client = client;
        this.collection = properties.getCollectionName() + COLLECTION_SUFFIX;
        this.timeoutMs = Math.max(2_000L, properties.getInitTimeoutMs());
    }

    @Override
    public String location() {
        return "qdrant:" + collection;
    }

    @Override
    public List<ReindexStateStore.PersistedTask> loadAll() {
        ensureCollection();
        List<ReindexStateStore.PersistedTask> tasks = new ArrayList<>();
        PointId offset = null;
        try {
            do {
                ScrollPoints.Builder request = ScrollPoints.newBuilder()
                        .setCollectionName(collection)
                        .setFilter(taskFilter())
                        .setLimit(PAGE_SIZE)
                        .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());
                if (offset != null) {
                    request.setOffset(offset);
                }
                ScrollResponse response = client.scrollAsync(request.build())
                        .get(timeoutMs, TimeUnit.MILLISECONDS);
                for (RetrievedPoint point : response.getResultList()) {
                    ReindexStateStore.PersistedTask task = readTask(point);
                    if (task != null) {
                        tasks.add(task);
                    }
                }
                offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
            } while (offset != null);
        } catch (Exception e) {
            invalidate();
            throw new IllegalStateException("读取重建任务状态失败: " + collection, e);
        }
        tasks.sort(Comparator.comparing(task -> String.valueOf(task.view().startedAt())));
        return tasks;
    }

    @Override
    public void save(ReindexStateStore.PersistedTask task) {
        ensureCollection();
        String json;
        try {
            json = objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException("序列化重建任务状态失败: " + task.view().taskId(), e);
        }
        PointStruct point = PointStruct.newBuilder()
                .setId(PointIdFactory.id(taskPointId(task.view().taskId())))
                .setVectors(dummyVector())
                .putPayload(FIELD_KIND, stringValue(KIND_TASK))
                .putPayload(FIELD_PAYLOAD, stringValue(json))
                .build();
        upsert(point);
    }

    @Override
    public void delete(String taskId) {
        ensureCollection();
        try {
            client.deleteAsync(collection, List.of(PointIdFactory.id(taskPointId(taskId))))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            invalidate();
            throw new IllegalStateException("删除重建任务状态失败: " + taskId, e);
        }
    }

    private void upsert(PointStruct point) {
        try {
            client.upsertAsync(collection, List.of(point)).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            invalidate();
            throw new IllegalStateException("写入重建任务状态失败: " + collection, e);
        }
    }

    private void invalidate() {
        collectionReady.set(false);
    }

    private ReindexStateStore.PersistedTask readTask(RetrievedPoint point) {
        Value payload = point.getPayloadMap().get(FIELD_PAYLOAD);
        if (payload == null || payload.getStringValue().isBlank()) {
            return null;
        }
        try {
            ReindexStateStore.PersistedTask task =
                    objectMapper.readValue(payload.getStringValue(), ReindexStateStore.PersistedTask.class);
            if (task.view() == null || task.view().taskId() == null) {
                log.warn("跳过不完整的重建任务状态: pointId={}", point.getId().getUuid());
                return null;
            }
            return task;
        } catch (Exception e) {
            log.warn("跳过损坏的重建任务状态: pointId={}, 原因={}",
                    point.getId().getUuid(), e.toString());
            return null;
        }
    }

    private void ensureCollection() {
        if (collectionReady.get()) {
            return;
        }
        try {
            Boolean exists = client.collectionExistsAsync(collection)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            if (!Boolean.TRUE.equals(exists)) {
                try {
                    client.createCollectionAsync(collection, VectorParams.newBuilder()
                                    .setSize(1)
                                    .setDistance(Distance.Dot)
                                    .build())
                            .get(timeoutMs, TimeUnit.MILLISECONDS);
                    log.info("重建任务状态集合创建成功: {}", collection);
                } catch (Exception createError) {
                    // 并发创建：另一个实例已经建好，视为成功
                    boolean nowExists = Boolean.TRUE.equals(client.collectionExistsAsync(collection)
                            .get(timeoutMs, TimeUnit.MILLISECONDS));
                    if (!nowExists) {
                        throw createError;
                    }
                }
            }
            collectionReady.set(true);
        } catch (Exception e) {
            throw new IllegalStateException("重建任务状态集合不可用: " + collection, e);
        }
    }

    private static Filter taskFilter() {
        return Filter.newBuilder()
                .addMust(Condition.newBuilder()
                        .setField(FieldCondition.newBuilder()
                                .setKey(FIELD_KIND)
                                .setMatch(Match.newBuilder().setKeyword(KIND_TASK))))
                .build();
    }

    private static Vectors dummyVector() {
        return Vectors.newBuilder()
                .setVector(Vector.newBuilder().addData(0.0f))
                .build();
    }

    private static Value stringValue(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }

    private static UUID taskPointId(String taskId) {
        return UUID.nameUUIDFromBytes((TASK_ID_PREFIX + taskId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 供测试与诊断：状态集合名。
     */
    String collection() {
        return collection;
    }
}
