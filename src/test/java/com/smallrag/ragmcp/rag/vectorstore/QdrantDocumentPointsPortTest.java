package com.smallrag.ragmcp.rag.vectorstore;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QdrantDocumentPointsPort} 的单元测试：mock gRPC 客户端验证
 * 枚举、删除、取回父块与孤儿清理的行为。
 *
 * @author zjk
 */
class QdrantDocumentPointsPortTest {

    private static RetrievedPoint point(UUID id, String chunkType, String parentId) {
        RetrievedPoint.Builder point = RetrievedPoint.newBuilder()
                .setId(PointIdFactory.id(id))
                .putPayload("chunk_type", Value.newBuilder().setStringValue(chunkType).build());
        if (parentId != null) {
            point.putPayload("parent_id", Value.newBuilder().setStringValue(parentId).build());
        }
        return point.build();
    }

    @Test
    void cleanupDeletesOrphanParentsAndLegacyPoints() throws Exception {
        QdrantClient client = mock(QdrantClient.class);
        QdrantProperties properties = new QdrantProperties();
        when(client.deleteAsync(anyString(), any(Filter.class)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.deleteAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        UUID liveParent = UUID.randomUUID();
        UUID orphanParent = UUID.randomUUID();
        when(client.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(Points.ScrollResponse.newBuilder()
                        .addResult(point(liveParent, "parent", "doc-1#p0"))
                        .addResult(point(orphanParent, "parent", "doc-1#p1"))
                        .addResult(point(UUID.randomUUID(), "child", "doc-1#p0"))
                        .addResult(point(UUID.randomUUID(), "chunk", null))
                        .build()));
        QdrantDocumentPointsPort port = new QdrantDocumentPointsPort(client, properties);

        CleanupResult result = port.cleanupOrphans();

        assertThat(result.parentPoints()).isEqualTo(2);
        assertThat(result.orphanParents()).isEqualTo(1);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<PointId>> deleted = ArgumentCaptor.forClass((Class) List.class);
        verify(client).deleteAsync(eq(properties.getCollectionName()), deleted.capture());
        assertThat(deleted.getValue())
                .extracting(PointId::getUuid)
                .containsExactly(orphanParent.toString());
    }

    @Test
    void findPointIdsReturnsEmptyOnScrollFailure() {
        QdrantClient client = mock(QdrantClient.class);
        QdrantProperties properties = new QdrantProperties();
        when(client.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(Futures.immediateFailedFuture(new IllegalStateException("scroll down")));
        QdrantDocumentPointsPort port = new QdrantDocumentPointsPort(client, properties);

        Optional<Set<String>> result = port.findPointIds("doc-1");

        assertThat(result).isEmpty();
    }

    @Test
    void findPointIdsExtractsPointIds() throws Exception {
        QdrantClient client = mock(QdrantClient.class);
        QdrantProperties properties = new QdrantProperties();
        UUID pointId = UUID.randomUUID();
        when(client.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(Points.ScrollResponse.newBuilder()
                        .addResult(RetrievedPoint.newBuilder()
                                .setId(PointIdFactory.id(pointId))
                                .build())
                        .build()));
        QdrantDocumentPointsPort port = new QdrantDocumentPointsPort(client, properties);

        Optional<Set<String>> result = port.findPointIds("doc-1");

        assertThat(result).contains(Set.of(pointId.toString()));
    }

    @Test
    void fetchParentsReturnsEmptyMapOnFailure() {
        QdrantClient client = mock(QdrantClient.class);
        QdrantProperties properties = new QdrantProperties();
        when(client.retrieveAsync(anyString(), anyList(), eq(true), eq(true), isNull()))
                .thenReturn(Futures.immediateFailedFuture(new IllegalStateException("qdrant down")));
        QdrantDocumentPointsPort port = new QdrantDocumentPointsPort(client, properties);

        assertThat(port.fetchParents(Set.of("doc-1#p0"))).isEmpty();
    }

    @Test
    void fetchParentsBuildsMapByParentId() {
        QdrantClient client = mock(QdrantClient.class);
        QdrantProperties properties = new QdrantProperties();
        UUID parentPointId = PointIds.parentPointUuid("doc-1#p0");
        RetrievedPoint retrieved = RetrievedPoint.newBuilder()
                .setId(PointIdFactory.id(parentPointId))
                .putPayload("content", Value.newBuilder().setStringValue("父块正文").build())
                .build();
        when(client.retrieveAsync(anyString(), anyList(), eq(true), eq(true), isNull()))
                .thenReturn(Futures.immediateFuture(List.of(retrieved)));
        QdrantDocumentPointsPort port = new QdrantDocumentPointsPort(client, properties);

        Map<String, ParentPoint> result = port.fetchParents(Set.of("doc-1#p0"));

        assertThat(result).containsKey("doc-1#p0");
        assertThat(result.get("doc-1#p0").text()).isEqualTo("父块正文");
    }
}
