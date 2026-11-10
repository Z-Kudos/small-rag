package com.smallrag.ragmcp.rag.reindex;

import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zjk
 */
class CancellationScopeTest {

    private static final Request REQUEST = new Request.Builder()
            .url("http://localhost:11434/v1/embeddings")
            .build();

    @Test
    void cancelsInFlightCall() throws Exception {
        Call call = mock(Call.class);
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.call()).thenReturn(call);
        when(chain.request()).thenReturn(REQUEST);

        CountDownLatch proceedEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(chain.proceed(any())).thenAnswer(invocation -> {
            proceedEntered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        });

        AtomicReference<CancellationScope> scopeRef = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                // 作用域绑定在线程上，因此必须在发起调用的同一线程 open
                try (CancellationScope scope = CancellationScope.open()) {
                    scopeRef.set(scope);
                    ready.countDown();
                    CancellationScope.interceptor().intercept(chain);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(proceedEntered.await(5, TimeUnit.SECONDS)).isTrue();

            scopeRef.get().cancel();

            verify(call).cancel();
            release.countDown();
            future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void refusesToProceedAfterCancelled() throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(REQUEST);

        try (CancellationScope scope = CancellationScope.open()) {
            scope.cancel();

            assertThatThrownBy(() -> CancellationScope.interceptor().intercept(chain))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("撤销");
            verify(chain, never()).proceed(any());
        }
    }

    @Test
    void passesThroughWithoutScope() throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(REQUEST);

        CancellationScope.interceptor().intercept(chain);

        verify(chain).proceed(REQUEST);
    }
}
