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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 核心服务：检索（VectorStore）→ 增强（拼装 Prompt）→ 生成（ChatClient）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    static final String NO_CONTENT_MESSAGE =
            "未检索到相关内容。请先通过 rag_ingest 向知识库写入文本，或换一个查询词重试。";

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

    /**
     * 检索知识库并生成增强回答。
     *
     * @param query 自然语言查询
     * @param topK  检索片段数量，null 或非正数时使用默认值
     * @return 基于知识库的回答，知识库为空时返回友好提示
     */
    public String query(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            return "查询内容不能为空。";
        }

        int limit = resolveTopK(topK);
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(limit).build());

        if (documents == null || documents.isEmpty()) {
            log.debug("知识库未命中，query={}", query);
            return NO_CONTENT_MESSAGE;
        }

        String context = documents.stream()
                .map(document -> "[" + document.getId() + "] " + document.getText())
                .collect(Collectors.joining("\n---\n"));

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
    }

    /**
     * 将文本写入知识库。
     *
     * @param text     文本内容
     * @param metadata 业务元数据，可为 null
     * @return 入库结果说明
     */
    public String ingest(String text, Map<String, String> metadata) {
        if (text == null || text.isBlank()) {
            return "入库失败：text 不能为空。";
        }

        Map<String, Object> documentMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            documentMetadata.putAll(metadata);
        }
        documentMetadata.putIfAbsent("ingested_at", Instant.now().toString());

        Document document = new Document(text, documentMetadata);
        vectorStore.add(List.of(document));
        log.info("文档已入库，id={}, metadata={}", document.getId(), documentMetadata);
        return "已入库，文档 id=" + document.getId();
    }

    private int resolveTopK(Integer topK) {
        int requested = (topK == null || topK <= 0) ? properties.getDefaultTopK() : topK;
        int max = Math.max(1, properties.getMaxTopK());
        return Math.min(requested, max);
    }
}
