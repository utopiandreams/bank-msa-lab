package lab.bank.bankmock.api

import lab.bank.bankmock.TestcontainersConfiguration
import lab.bank.bankmock.application.BankMockSwitches
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 실험 스위치 API: 진짜 관리 포트로 불러 JSON → Duration 변환, 보낸 필드만 바뀌는지, 잘못된 값은 400인지 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["management.server.port=0"])
@Import(TestcontainersConfiguration::class)
class ExperimentsEndpointTest {

    @LocalManagementPort var port: Int = 0
    @Autowired lateinit var switches: BankMockSwitches
    private val http = HttpClient.newHttpClient()

    private fun post(json: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$port/actuator/experiments"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @AfterEach
    fun reset() { post("""{"reset":true}""") }

    @Test
    fun `보낸 필드만 바뀐다 - 재시작 없이`() {
        val res = post("""{"responseDelay":"5s","responseDelayPercent":2}""")
        assertEquals(200, res.statusCode(), res.body())
        assertEquals(Duration.ofSeconds(5), switches.current.responseDelay)
        assertEquals(2, switches.current.responseDelayPercent)
        assertEquals(0, switches.current.rejectRatePercent)
        assertFalse(switches.current.lookupLiesNotFound)
    }

    @Test
    fun `reset이면 설정값으로 돌아간다`() {
        post("""{"lookupLiesNotFound":true}""")
        post("""{"reset":true}""")
        assertFalse(switches.current.lookupLiesNotFound)
    }

    @Test
    fun `범위 밖 값은 400 - 스위치는 그대로`() {
        val res = post("""{"responseDelayPercent":150}""")
        assertEquals(400, res.statusCode(), res.body())
        assertEquals(100, switches.current.responseDelayPercent)
    }
}
