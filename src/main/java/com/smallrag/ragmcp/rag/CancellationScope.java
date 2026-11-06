package com.smallrag.ragmcp.rag;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Interceptor;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务级主动取消作用域：把取消信号与该任务发起的 OkHttp 调用关联起来。
 *
 * <p>重建线程在任务开始时 {@link #open()}（绑定到当前线程），OpenAI SDK 的 OkHttp
 * 拦截器通过 {@link #interceptor()} 把在途 {@link Call} 登记进来；看门狗硬超时调用
 * {@link #cancel()} 直接取消这些调用，避免线程一直卡在 socket 读上直到 read timeout。
 *
 * <p>作用域通过 {@link ThreadLocal} 绑定，因此必须在同一线程内 open / 发起调用 / close。
 * 非重建线程没有绑定作用域，拦截器直接放行。
 *
 * @author zjk
 */
@Slf4j
public final class CancellationScope implements AutoCloseable {

    private static final ThreadLocal<CancellationScope> CURRENT = new ThreadLocal<>();

    private final Set<Call> inFlight = ConcurrentHashMap.newKeySet();

    private volatile boolean cancelled;

    private CancellationScope() {
    }

    /**
     * 在当前线程打开作用域；必须用 try-with-resources 关闭。
     *
     * @return 新作用域
     */
    public static CancellationScope open() {
        CancellationScope scope = new CancellationScope();
        CURRENT.set(scope);
        return scope;
    }

    /**
     * 注册到 OpenAI SDK 的 OkHttp 客户端：把在途调用登记到当前线程的作用域。
     *
     * <p>没有绑定作用域（普通入库 / 检索调用）时直接放行。
     *
     * @return OkHttp 拦截器
     */
    public static Interceptor interceptor() {
        return chain -> {
            CancellationScope scope = CURRENT.get();
            if (scope == null) {
                return chain.proceed(chain.request());
            }
            if (scope.cancelled) {
                throw new IOException("重建任务已撤销，调用未发出");
            }
            Call call = chain.call();
            scope.inFlight.add(call);
            try {
                if (scope.cancelled) {
                    throw new IOException("重建任务已撤销，调用未发出");
                }
                return chain.proceed(chain.request());
            } finally {
                scope.inFlight.remove(call);
            }
        };
    }

    /**
     * 取消作用域内所有在途调用；可重复调用。
     */
    public void cancel() {
        cancelled = true;
        int count = inFlight.size();
        for (Call call : inFlight) {
            call.cancel();
        }
        if (count > 0) {
            log.debug("已主动取消 {} 个在途 Embedding 调用", count);
        }
    }

    /**
     * @return 是否已取消
     */
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void close() {
        cancelled = true;
        if (CURRENT.get() == this) {
            CURRENT.remove();
        }
    }
}
