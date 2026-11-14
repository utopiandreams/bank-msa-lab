package lab.bank.gateway

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 인증 테스트 (ADR-0002). gateway를 진짜로 띄우고 transfer 자리에 FakeTransfer를 둔다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayAuthTest {

    @LocalServerPort
    private var port: Int = 0

    private val client by lazy { WebTestClient.bindToServer().baseUrl("http://localhost:$port").build() }

    @BeforeEach
    fun clear() = FakeTransfer.received.clear()

    // 재현: 철수의 진짜 토큰 + 손으로 붙인 X-User-Id: younghee → transfer가 영희로 알면 영희 행세가 된다
    @Test
    fun `위조 헤더 - 들어온 X-User-Id는 버리고 토큰의 sub로 덮어쓴다`() {
        client.get().uri("/transfers/abc")
            .header("Authorization", "Bearer ${TestJwt.hs256("chulsoo")}")
            .header("X-User-Id", "younghee")
            .exchange()
            .expectStatus().isCreated

        assertEquals(listOf("chulsoo"), FakeTransfer.received.single().header("X-User-Id"))
    }

    @Test
    fun `토큰이 있으면 sub를 X-User-Id로 붙여 전달한다`() {
        client.get().uri("/transfers/abc")
            .header("Authorization", "Bearer ${TestJwt.hs256("chulsoo")}")
            .exchange()
            .expectStatus().isCreated

        assertEquals(listOf("chulsoo"), FakeTransfer.received.single().header("X-User-Id"))
    }

    @Test
    fun `토큰 없음 - 401이고 transfer에 닿지 않는다`() {
        client.get().uri("/transfers/abc")
            .header("X-User-Id", "younghee")   // 헤더만으로 행세하려는 시도
            .exchange()
            .expectStatus().isUnauthorized
            .expectHeader().exists("WWW-Authenticate")

        assertTrue(FakeTransfer.received.isEmpty())
    }

    @Test
    fun `다른 키로 서명한 토큰 - 401`() {
        assertRejected(TestJwt.hs256("chulsoo", secret = "attacker-secret-key-at-least-32-bytes!!"))
    }

    @Test
    fun `만료된 토큰 - 401 (시계 오차 허용 60초를 넘긴 것)`() {
        assertRejected(TestJwt.hs256("chulsoo", exp = Instant.now().minusSeconds(120)))
    }

    @Test
    fun `다른 발급자(iss)의 토큰 - 401`() {
        assertRejected(TestJwt.hs256("chulsoo", issuer = "someone-else"))
    }

    @Test
    fun `alg=none 바꿔치기 - 서명이 없어도 받아 주면 안 된다`() {
        assertRejected(TestJwt.algNone("chulsoo"))
    }

    @Test
    fun `페이로드를 고치면 서명이 안 맞는다 - sub를 younghee로 바꿔치기`() {
        val (header, _, signature) = TestJwt.hs256("chulsoo").split(".")
        val forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"younghee","iss":"${TestJwt.ISSUER}","exp":${Instant.now().plusSeconds(600).epochSecond}}""".toByteArray()
        )
        assertRejected("$header.$forgedPayload.$signature")
    }

    @Test
    fun `발급 API로 받은 토큰으로 통과한다`() {
        val body = client.post().uri("/auth/token")
            .header("Content-Type", "application/json")
            .bodyValue("""{"userId":"chulsoo"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.token_type").isEqualTo("Bearer")
            .jsonPath("$.expires_in").isEqualTo(900)
            .returnResult().responseBody!!.decodeToString()
        val token = Regex("\"access_token\":\"([^\"]+)\"").find(body)!!.groupValues[1]

        client.get().uri("/transfers/abc")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isCreated

        assertEquals(listOf("chulsoo"), FakeTransfer.received.single().header("X-User-Id"))
    }

    @Test
    fun `발급 API - userId가 비었으면 400`() {
        client.post().uri("/auth/token")
            .header("Content-Type", "application/json")
            .bodyValue("""{"userId":"  "}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    private fun assertRejected(token: String) {
        client.get().uri("/transfers/abc")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isUnauthorized

        assertTrue(FakeTransfer.received.isEmpty())
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
