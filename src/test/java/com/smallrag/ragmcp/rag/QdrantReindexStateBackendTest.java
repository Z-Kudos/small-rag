package com.smallrag.ragmcp.rag;

import com.google.common.util.concurrent.Futures;
import com.smallrag.ragmcp.config.QdrantProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionOperationResponse;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author zjk
 */
class QdrantReindexStateBackendTest {

    private static final String COLLECTION = "small_rag_text_reindex_state";

    private final Map<String, PointStruct> points = new ConcurrentHashMap<>();
    private QdrantReindexStateBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        QdrantClient client = mock(QdrantClient.class);
        when(client.collectionExistsAsync(COLLECTION)).thenReturn(Futures.immediateFuture(false));
        when(client.createCollectionAsync(eq(COLLECTION), any(VectorParams.class)))
                .thenReturn(Futures.immediateFuture(CollectionOperationResponse.getDefaultInstance()));
        when(client.upsertAsync(eq(COLLECTION), anyList())).thenAnswer(invocation -> {
            List<PointStruct> batch = invocation.getArgument(1);
            batch.forEach(point -> points.put(point.getId().getUuid(), point));
            return Futures.immediateFuture(UpdateResult.getDefaultInstance());
        });
        when(client.deleteAsync(eq(COLLECTION), anyList())).thenAnswer(invocation -> {
            List<PointId> ids = invocation.getArgument(1);
            ids.forEach(id -> points.remove(id.getUuid()));
            return Futures.immediateFuture(UpdateResult.getDefaultInstance());
        });
        when(client.scrollAsync(any(ScrollPoints.class))).thenAnswer(invocation -> {
            List<RetrievedPoint> results = points.values().stream()
                    .filter(point -> "task".equals(
                            point.getPayloadMap().get("kind").getStringValue()))
                    .map(this::toRetrieved)
                    .toList();
            return Futures.immediateFuture(
                    ScrollResponse.newBuilder().addAllResult(results).build());
        });
        when(client.retrieveAsync(eq(COLLECTION), anyList(), anyBoolean(), anyBoolean(), isNull()))
                .thenAnswer(invocation -> {
                    List<PointId> ids = invocation.getArgument(1);
                    List<RetrievedPoint> results = new ArrayList<>(ids.size());
                    for (PointId id : ids) {
                        PointStruct point = points.get(id.getUuid());
                        if (point != null) {
                            results.add(toRetrieved(point));
                        }
                    }
                    return Futures.immediateFuture(results);
                });

        QdrantProperties properties = new QdrantProperties();
        properties.setCollectionName("small_rag_text");
        properties.setInitTimeoutMs(2_000);
        backend = new QdrantReindexStateBackend(JsonMapper.builder().build(), properties, client);
    }

    @Test
    void usesDedicatedStateCollection() {
        assertThat(backend.collection()).isEqualTo(COLLECTION);
        assertThat(backend.location()).isEqualTo("qdrant:" + COLLECTION);
    }

    @Test
    void roundTripsTaskAndLockThroughQdrant() {
        ReindexStateStore.PersistedTask task = new ReindexStateStore.PersistedTask(
                view("task-1"), true, Set.of("doc-1", "doc-2"));

        backend.save(task);
        assertThat(backend.loadAll()).containsExactly(task);

        backend.writeOwner("task-1");
        assertThat(backend.readOwner()).contains("task-1");

        // 非持有者不能清锁
        backend.clearOwner("task-2");
        assertThat(backend.readOwner()).contains("task-1");

        backend.clearOwner("task-1");
        assertThat(backend.readOwner()).isEmpty();

        backend.delete("task-1");
        assertThat(backend.loadAll()).isEmpty();
    }

    @Test
    void skipsCorruptedTaskPayload() {
        points.put("broken", PointStruct.newBuilder()
                .setId(io.qdrant.client.PointIdFactory.id(java.util.UUID.randomUUID()))
                .putPayload("kind", io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                        .setStringValue("task").build())
                .putPayload("payload", io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                        .setStringValue("{not-json").build())
                .build());

        assertThat(backend.loadAll()).isEmpty();
    }

    private RetrievedPoint toRetrieved(PointStruct point) {
        return RetrievedPoint.newBuilder()
                .setId(point.getId())
                .putAllPayload(point.getPayloadMap())
                .build();
    }

    private static ReindexTaskService.ReindexTaskView view(String taskId) {
        String now = Instant.now().toString();
        return new ReindexTaskService.ReindexTaskView(taskId, "reindex", "reindexing", 2, 1,
                1, 0, 50, "doc-1", now, false, false, now, null,
                false, null, false, null);
    }
}
