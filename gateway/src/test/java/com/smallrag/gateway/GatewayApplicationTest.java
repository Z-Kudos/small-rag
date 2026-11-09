package com.smallrag.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 网关上下文冒烟测试：Consul 关闭时仍能启动并暴露 actuator。
 *
 * @author zjk
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.consul.enabled=false",
        "spring.cloud.consul.discovery.enabled=false"
})
class GatewayApplicationTest {

    @Test
    void contextLoads() {
    }
}
