package lab.bank.gateway

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertTrue

/**
 * 지표는 관리 포트에서만 (2026-11-18). gateway는 Ingress로 인터넷에 열린 유일한 입구라서,
 * actuator가 앱 포트에 있으면 /actuator/prometheus(경로별 호출 수·에러율·JVM 정보)가 밖에서 보인다.
 * RANDOM_PORT 테스트에서는 management.server.port도 무작위로 바뀐다 (Boot 테스트 지원).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagementPortTest {

    @LocalServerPort
    private var port: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    private fun client(p: Int) = WebTestClient.bindToServer().baseUrl("http://localhost:$p").build()

    @Test
    fun `관리 포트 - 토큰 없이 Prometheus 지표를 내놓는다`() {
        val body = client(managementPort).get().uri("/actuator/prometheus")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!

        assertTrue(body.contains("""application="gateway""""), "모든 지표에 서비스 이름 라벨이 붙어야 한다")
        assertTrue(body.contains("jvm_memory_used_bytes"))
    }

    @Test
    fun `앱 포트 - actuator는 없다 (밖에서 지표가 안 보인다)`() {
        // 토큰을 붙여도 없어야 한다: 인증 문제가 아니라 그 포트에 엔드포인트가 아예 없어야 한다
        client(port).get().uri("/actuator/prometheus")
            .header("Authorization", "Bearer ${TestJwt.hs256("chulsoo")}")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `앱 포트 - 프로브는 토큰 없이 열려 있다`() {
        client(port).get().uri("/livez").exchange().expectStatus().isOk
        client(port).get().uri("/readyz").exchange().expectStatus().isOk
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("TRANSFER_BASE_URL") { FakeTransfer.baseUrl }
            registry.add("CORE_BASE_URL") { FakeTransfer.baseUrl }
            registry.add("gateway.jwt.secret") { TestJwt.SECRET }
        }
    }
}
