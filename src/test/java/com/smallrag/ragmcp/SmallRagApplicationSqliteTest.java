package com.smallrag.ragmcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * SQLite 后端的应用上下文冒烟测试：rag.vector-store.type=sqlite 时
 * 装配、schema 创建与启动校验成立，且不依赖任何外部服务。
 *
 * @author zjk
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.vector-store.type=sqlite",
        "rag.vector-store.sqlite.path=${java.io.tmpdir}/smallrag-context-test.sqlite",
        "rag.vector-store.sqlite.dimension=8",
        "rag.archive-dir=${java.io.tmpdir}/small-rag-context-test-sqlite",
        "smallrag.distributed.enabled=false",
        "spring.cloud.config.enabled=false"
})
class SmallRagApplicationSqliteTest {

    @Test
    void contextLoads() {
    }
}
