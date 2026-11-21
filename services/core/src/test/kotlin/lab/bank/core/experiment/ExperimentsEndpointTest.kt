package lab.bank.core.experiment

import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.account.application.balance.LockStrategySwitch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

/**
 * 실험 스위치 API: 관리 포트에서만 바꿀 수 있고, 업무 포트(8080 = gateway 뒤)에는 없다.
 * 실제 포트로 띄운다 (관리 포트가 따로라 MockMvc로는 actuator가 안 보인다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
@Import(TestcontainersConfiguration::class)
class ExperimentsEndpointTest {

    @LocalServerPort var appPort: Int = 0
    @LocalManagementPort var managementPort: Int = 0
    @Autowired lateinit var lockSwitch: LockStrategySwitch

    private val client = RestClient.create()

    @AfterEach
    fun reset() {
        lockSwitch.change(LockStrategy.OPTIMISTIC)
    }

    @Test
    fun `관리 포트에서 락 전략을 읽고 바꾼다 - 바로 다음 요청부터 적용`() {
        val url = "http://localhost:$managementPort/actuator/experiments"

        val before = client.get().uri(url).retrieve().body(Map::class.java)!!
        val changed = client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
            .body("""{"lockStrategy":"PESSIMISTIC"}""").retrieve().body(Map::class.java)!!

        assertEquals("OPTIMISTIC", before["lockStrategy"])
        assertEquals("PESSIMISTIC", changed["lockStrategy"])
        assertEquals("OPTIMISTIC", changed["previous"])
        assertEquals(LockStrategy.PESSIMISTIC, lockSwitch.strategy)
    }

    @Test
    fun `없는 전략 이름은 400 - 스위치는 그대로`() {
        val status = client.post().uri("http://localhost:$managementPort/actuator/experiments")
            .contentType(MediaType.APPLICATION_JSON).body("""{"lockStrategy":"YOLO"}""")
            .exchange { _, response -> response.statusCode }

        assertEquals(HttpStatusCode.valueOf(400), status)
        assertEquals(LockStrategy.OPTIMISTIC, lockSwitch.strategy)
    }

    @Test
    fun `업무 포트(gateway가 보는 8080)에는 스위치가 없다`() {
        val status = client.get().uri("http://localhost:$appPort/actuator/experiments")
            .exchange { _, response -> response.statusCode }

        assertEquals(HttpStatusCode.valueOf(404), status)
    }
}
