package com.smallrag.ragmcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 应用上下文冒烟测试：不依赖 Qdrant / Embedding 服务，验证 Spring Cloud
 * CircuitBreaker / Config Client 等自动配置可以完成装配。
 *
 * @author zjk
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.archive-dir=${java.io.tmpdir}/small-rag-context-test",
        "smallrag.distributed.enabled=false",
        "spring.cloud.config.enabled=false"
})
class SmallRagApplicationTest {

    @Test
    void contextLoads() {
    }
}
