package lab.bank.gateway

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 라우팅 테스트: gateway를 진짜로 띄우고(랜덤 포트), transfer 자리에 FakeTransfer를 둔다.
 * 인증은 GatewayAuthTest에서 본다. 여기서는 유효한 토큰을 붙이고 "무엇이 어디로 전달되나"만 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    @LocalServerPort
    private var port: Int = 0

    private val client by lazy {
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .defaultHeader("Authorization", "Bearer ${TestJwt.hs256("chulsoo")}")
            .build()
    }

    @BeforeEach
    fun clear() = FakeTransfer.received.clear()

    @Test
    fun `transfers 경로는 경로·메서드·헤더·본문을 그대로 transfer로 넘긴다`() {
        client.post().uri("/transfers")
            .header("Idempotency-Key", "k-1")
            .header("Content-Type", "application/json")
            .bodyValue("""{"amount":1000}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody(String::class.java).isEqualTo("transfer-fake")

        val r = FakeTransfer.received.single()
        assertEquals("POST", r.method)
        assertEquals("/transfers", r.path)
        assertEquals(listOf("k-1"), r.header("Idempotency-Key"))
        assertEquals("""{"amount":1000}""", r.body)
    }

    @Test
    fun `하위 경로와 쿼리도 그대로 넘긴다`() {
        client.get().uri("/transfers/abc?x=1").exchange().expectStatus().isCreated

        assertEquals("/transfers/abc?x=1", FakeTransfer.received.single().path)
    }

    @Test
    fun `core의 postings는 gateway에 열지 않는다 - 토큰이 있어도 404이고 아무 데도 전달되지 않는다`() {
        client.post().uri("/postings/transfers").exchange().expectStatus().isNotFound

        assertTrue(FakeTransfer.received.isEmpty())
    }

    @Test
    fun `gateway 자신의 health는 토큰 없이 열려 있다 (쿠버네티스 프로브)`() {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
            .get().uri("/actuator/health").exchange().expectStatus().isOk
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("TRANSFER_BASE_URL") { FakeTransfer.baseUrl }
            registry.add("gateway.jwt.secret") { TestJwt.SECRET }
        }
    }
}
