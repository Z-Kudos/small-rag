package com.octopus.ragmcp.rag;

import com.octopus.ragmcp.config.RagProperties;
import com.octopus.ragmcp.store.InMemoryVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RagServiceTest {

    @Test
    void emptyKnowledgeBaseReturnsFriendlyMessageWithoutCallingModel() {
        EmbeddingModel embeddingModel = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new AssertionError("空知识库不应调用 Embedding 模型");
            }

            @Override
            public float[] embed(Document document) {
                throw new AssertionError("空知识库不应调用 Embedding 模型");
            }
        };

        RagService service = new RagService(
                new InMemoryVectorStore(embeddingModel),
                mock(ChatClient.class),
                new RagProperties());

        assertThat(service.query("项目支持哪些向量数据库？", 3))
                .isEqualTo(RagService.NO_CONTENT_MESSAGE);
    }

    @Test
    void blankIngestTextIsRejected() {
        RagService service = new RagService(
                new InMemoryVectorStore(mock(EmbeddingModel.class)),
                mock(ChatClient.class),
                new RagProperties());

        assertThat(service.ingest("  ", null)).contains("不能为空");
    }
}
