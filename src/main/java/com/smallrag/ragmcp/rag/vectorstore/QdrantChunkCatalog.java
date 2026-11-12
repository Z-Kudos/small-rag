package com.smallrag.ragmcp.rag.vectorstore;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.rag.RagService;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.FieldCondition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Match;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.ListValue;
import io.qdrant.client.grpc.JsonWithInt.Struct;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 实现的 {@link ChunkCatalog}：scroll 全部 point 或带 doc_id 过滤读出 chunk。
 *
 * @author zjk
 */
@Slf4j
public class QdrantChunkCatalog implements ChunkCatalog {

    private static final int PAGE_SIZE = 256;

    private final QdrantClient client;
    private final QdrantProperties properties;

    public QdrantChunkCatalog(QdrantClient client, QdrantProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public List<ChunkSummary> listChunks(String docId) throws IllegalStateException {
        long timeout = Math.max(1L, properties.getRequestTimeoutMs());
        Filter filter = docId == null || docId.isBlank() ? null : Filter.newBuilder()
                .addMust(Condition.newBuilder()
                        .setField(FieldCondition.newBuilder()
                                .setKey("doc_id")
                                .setMatch(Match.newBuilder().setKeyword(docId))))
                .build();
        List<ChunkSummary> result = new ArrayList<>();
        PointId offset = null;
        try {
            do {
                ScrollPoints.Builder request = ScrollPoints.newBuilder()
                        .setCollectionName(properties.getCollectionName())
                        .setLimit(PAGE_SIZE)
                        .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());
                if (filter != null) {
                    request.setFilter(filter);
                }
                if (offset != null) {
                    request.setOffset(offset);
                }
                ScrollResponse response = client.scrollAsync(request.build())
                        .get(timeout, TimeUnit.MILLISECONDS);
                for (RetrievedPoint point : response.getResultList()) {
                    ChunkSummary summary = toSummary(point);
                    if (summary != null) {
                        result.add(summary);
                    }
                }
                offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
            } while (offset != null);
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant 枚举 chunk 失败: 集合=" + properties.getCollectionName()
                    + "，原因=" + e.getMessage(), e);
        }
        return result;
    }

    private ChunkSummary toSummary(RetrievedPoint point) {
        Map<String, Value> payload = point.getPayloadMap();
        Value content = payload.get(RagService.CONTENT_FIELD);
        if (content == null || !content.hasStringValue()) {
            log.debug("跳过无 content 的 point: {}", point.getId().getUuid());
            return null;
        }
        Map<String, Object> metadata = new HashMap<>();
        for (Map.Entry<String, Value> entry : payload.entrySet()) {
            if (RagService.CONTENT_FIELD.equals(entry.getKey())) {
                continue;
            }
            metadata.put(entry.getKey(), valueToObject(entry.getValue()));
        }
        return new ChunkSummary(point.getId().getUuid(), content.getStringValue(), metadata);
    }

    private static Object valueToObject(Value value) {
        if (value == null) {
            return null;
        }
        return switch (value.getKindCase()) {
            case STRING_VALUE -> value.getStringValue();
            case INTEGER_VALUE -> value.getIntegerValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue();
            case LIST_VALUE -> listToObjects(value.getListValue());
            case STRUCT_VALUE -> structToMap(value.getStructValue());
            default -> null;
        };
    }

    private static List<Object> listToObjects(ListValue list) {
        List<Object> result = new ArrayList<>(list.getValuesCount());
        for (Value item : list.getValuesList()) {
            result.add(valueToObject(item));
        }
        return result;
    }

    private static Map<String, Object> structToMap(Struct struct) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
            result.put(entry.getKey(), valueToObject(entry.getValue()));
        }
        return result;
    }
}
