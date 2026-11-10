package com.smallrag.ragmcp.config;

import com.openai.core.Timeout;
import okhttp3.Interceptor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zjk
 */
class EmbeddingConfigTest {

    @Test
    void appliesConfiguredTimeoutsToHttpClient() {
        RagProperties properties = new RagProperties();
        properties.getEmbedding().setConnectTimeoutMs(2_000);
        properties.getEmbedding().setReadTimeoutMs(3_000);

        SpringAiOpenAiHttpClient.Builder builder = mock(SpringAiOpenAiHttpClient.Builder.class);
        when(builder.timeout(any(Timeout.class))).thenReturn(builder);
        when(builder.interceptor(any(Interceptor.class))).thenReturn(builder);

        new EmbeddingConfig().embeddingHttpTimeoutCustomizer(properties).customize(builder);

        ArgumentCaptor<Timeout> captor = ArgumentCaptor.forClass(Timeout.class);
        verify(builder).timeout(captor.capture());
        Timeout timeout = captor.getValue();
        assertThat(timeout.connect()).isEqualTo(Duration.ofSeconds(2));
        assertThat(timeout.read()).isEqualTo(Duration.ofSeconds(3));
        assertThat(timeout.write()).isEqualTo(Duration.ofSeconds(3));
        assertThat(timeout.request()).isEqualTo(Duration.ofSeconds(5));

        // 任务级主动取消：拦截器必须注册到同一个 HTTP 客户端
        verify(builder).interceptor(any(Interceptor.class));
    }
}
