package com.smallrag.ragmcp.rag;

/**
 * 检索熔断打开时抛出，调用方应返回可读提示而不是重试。
 *
 * @author zjk
 */
public class QueryUnavailableException extends RuntimeException {

    /**
     * @param message 提示信息
     * @param cause   原因
     */
    public QueryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
