package com.octopus.ragmcp.rag;

import com.octopus.ragmcp.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG 核心服务：检索（Qdrant / VectorStore）→ 增强（拼装 Prompt）→ 生成（ChatClient）。
 *
 * <p>设计参考 whale 的 RagUtil：分块入库、相似度过滤、上下文预算、空上下文短路。
 * 只处理纯文本，不涉及 Tika / OCR / 图谱。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的知识库问答助手。只能依据用户消息中的“参考资料”回答问题。
            如果参考资料不足以回答，请明确说明“知识库中没有足够信息”，禁止编造。
            使用中文回答，并尽量标注参考片段序号。
            """;

    private static final String USER_TEMPLATE = """
            参考资料：
            {context}

            问题：{question}
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final RagProperties properties;
    private final KnowledgeBaseProbe knowledgeBaseProbe;

    /**
     * 检索知识库并生成增强回答。
     *
     * @param query 自然语言查询
     * @param topK  检索片段数量，null 或非正数时使用默认值
     * @return 基于知识库的回答；知识库为空时返回固定提示，不调用模型
     */
    public String query(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            return "查询内容不能为空。";
        }

        // 空知识库/无命中都应短路，避免为注定无结果的检索调用 Embedding 模型
        List<Document> documents;
        try {
            documents = search(query, topK);
        } catch (Exception e) {
            log.warn("知识库检索失败: query={}, 原因={}", query, e.toString());
            return "知识库检索失败，请确认 Qdrant 与 Embedding 模型可用：" + rootMessage(e);
        }

        if (documents.isEmpty()) {
            log.debug("知识库未命中，query={}", query);
            return properties.getEmptyContextAnswer();
        }

        String context = buildContext(documents);
        try {
            String answer = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(user -> user.text(USER_TEMPLATE)
                            .param("context", context)
                            .param("question", query))
                    .call()
                    .content();
            if (answer == null || answer.isBlank()) {
                return "模型未返回有效回答，请稍后重试。";
            }
            return answer;
        } catch (Exception e) {
            log.warn("回答生成失败: query={}, 原因={}", query, e.toString());
            return "回答生成失败，请确认 Chat 模型配置可用：" + rootMessage(e);
        }
    }

    /**
     * 仅做向量检索，返回命中分块（供 rag_search 使用）。
     *
     * @param query 自然语言查询
     * @param topK  返回片段数，null 或非正数时使用默认值
     * @return 按相似度降序排列的分块
     */
    public List<Document> search(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (knowledgeBaseProbe.isEmpty()) {
            return List.of();
        }
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(resolveTopK(topK))
                .similarityThreshold(properties.getMinScore())
                .build());
    }

    /**
     * 将纯文本切分为定长重叠分块后写入 Qdrant。
     *
     * @param text     文本内容
     * @param metadata 业务元数据，可为 null
     * @return 文档 id 与分块数
     */
    public IngestResult ingest(String text, Map<String, String> metadata) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }

        List<String> chunks = TextChunker.chunk(text, properties.getChunkSize(), properties.getChunkOverlap());
        String documentId = UUID.randomUUID().toString();
        String ingestedAt = Instant.now().toString();

        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> documentMetadata = new LinkedHashMap<>();
            if (metadata != null) {
                documentMetadata.putAll(metadata);
            }
            documentMetadata.put("doc_id", documentId);
            documentMetadata.put("chunk_index", i);
            documentMetadata.put("chunk_count", chunks.size());
            documentMetadata.put("ingested_at", ingestedAt);
            documents.add(new Document(chunks.get(i), documentMetadata));
        }

        vectorStore.add(documents);
        log.info("文档已入库: docId={}, chunks={}, metadata={}", documentId, chunks.size(), metadata);
        return new IngestResult(documentId, chunks.size());
    }

    private String buildContext(List<Document> documents) {
        int maxContextChars = Math.max(1, properties.getMaxContextChars());
        int maxChunkChars = Math.max(1, properties.getMaxChunkChars());

        StringBuilder context = new StringBuilder();
        int index = 1;
        for (Document document : documents) {
            String text = document.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (text.length() > maxChunkChars) {
                text = text.substring(0, maxChunkChars);
            }
            if (context.length() + text.length() > maxContextChars) {
                break;
            }
            context.append('[').append(index++).append("] ").append(text).append('\n');
        }
        return context.toString();
    }

    private int resolveTopK(Integer topK) {
        int requested = (topK == null || topK <= 0) ? properties.getDefaultTopK() : topK;
        int max = Math.max(1, properties.getMaxTopK());
        return Math.min(requested, max);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }

    /**
     * 入库结果。
     */
    public record IngestResult(String documentId, int chunkCount) {
    }
}
