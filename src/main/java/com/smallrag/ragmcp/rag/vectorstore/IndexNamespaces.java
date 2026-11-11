package com.smallrag.ragmcp.rag.vectorstore;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;

/**
 * 切分签名的命名空间：不同向量库后端的数据互不可见，
 * 因此自动重切签名也必须按后端隔离，避免“归档显示已索引、目标后端为空”。
 *
 * @author zjk
 */
public final class IndexNamespaces {

    /** SQLite 后端的固定签名命名空间。 */
    public static final String SQLITE_NAMESPACE = "sqlite";

    private IndexNamespaces() {
    }

    /**
     * @return Qdrant 后端为集合名；SQLite 后端为固定值 {@value #SQLITE_NAMESPACE}
     */
    public static String of(RagProperties rag, QdrantProperties qdrant) {
        return rag.getVectorStore().isSqlite() ? SQLITE_NAMESPACE : qdrant.getCollectionName();
    }
}
