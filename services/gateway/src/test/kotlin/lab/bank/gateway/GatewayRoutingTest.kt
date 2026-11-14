package lab.bank.gateway

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 라우팅 테스트: gateway를 진짜로 띄우고(랜덤 포트), transfer 자리에 JDK HttpServer로 만든 가짜 서버를 둔다.
 * 가짜 서버는 받은 요청(메서드·경로·헤더)을 기록하고 그대로 되돌려 준다 → "무엇이 전달됐나"를 검사한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    @LocalServerPort
    private var port: Int = 0

    private val client by lazy { WebTestClient.bindToServer().baseUrl("http://localhost:$port").build() }

    @BeforeEach
    fun clear() = received.clear()

    @Test
    fun `transfers 경로는 경로·메서드·헤더·본문을 그대로 transfer로 넘긴다`() {
        client.post().uri("/transfers")
            .header("Idempotency-Key", "k-1")
            .header("Content-Type", "application/json")
            .bodyValue("""{"amount":1000}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody(String::class.java).isEqualTo("transfer-fake")

        val r = received.single()
        assertEquals("POST", r.method)
        assertEquals("/transfers", r.path)
        assertEquals("k-1", r.idempotencyKey)
        assertEquals("""{"amount":1000}""", r.body)
    }

    @Test
    fun `하위 경로와 쿼리도 그대로 넘긴다`() {
        client.get().uri("/transfers/abc?x=1").exchange().expectStatus().isCreated

        assertEquals("/transfers/abc?x=1", received.single().path)
    }

    @Test
    fun `core의 postings는 gateway에 열지 않는다 - 404이고 아무 데도 전달되지 않는다`() {
        client.post().uri("/postings/transfers").exchange().expectStatus().isNotFound

        assertTrue(received.isEmpty())
    }

    @Test
    fun `gateway 자신의 health는 열려 있다`() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk
    }

    data class Received(val method: String, val path: String, val idempotencyKey: String?, val body: String)

    companion object {
        private val received = CopyOnWriteArrayList<Received>()

        private val fakeTransfer: HttpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/") { ex ->
                received += Received(
                    method = ex.requestMethod,
                    path = ex.requestURI.toString(),
                    idempotencyKey = ex.requestHeaders.getFirst("Idempotency-Key"),
                    body = ex.requestBody.readAllBytes().decodeToString(),
                )
                val bytes = "transfer-fake".toByteArray()
                ex.sendResponseHeaders(201, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun transferUrl(registry: DynamicPropertyRegistry) {
            registry.add("TRANSFER_BASE_URL") { "http://localhost:${fakeTransfer.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = fakeTransfer.stop(0)
    }
}
