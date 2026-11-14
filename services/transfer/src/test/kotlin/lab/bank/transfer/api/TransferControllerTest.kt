package lab.bank.transfer.api

import com.jayway.jsonpath.JsonPath
import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.FakeCorePostingPort
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.CoreTransferResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// HTTP 계층 테스트: 상태 코드·헤더·예외 매핑 (상태 전이·트랜잭션은 TransferUseCaseTest가 검사)
// 핵심 확인: 고객이 고칠 수 있는 실수는 전부 400이고, 그때 core를 부르지 않는다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class TransferControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var fakeCore: FakeCorePostingPort

    private val from = UUID.randomUUID()
    private val to = UUID.randomUUID()

    @BeforeEach
    fun setUp() = fakeCore.reset()

    private fun newKey() = "key-${UUID.randomUUID()}"

    // key·user = null이면 헤더를 아예 안 보낸다. user는 gateway가 토큰의 sub로 붙여 주는 값 (ADR-0002)
    private fun postTransfer(body: String, key: String? = newKey(), user: String? = "chulsoo"): ResultActionsDsl =
        mockMvc.post("/transfers") {
            key?.let { header("Idempotency-Key", it) }
            user?.let { header("X-User-Id", it) }
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun postTransfer(from: UUID, to: UUID, amount: Long, key: String? = newKey(), user: String? = "chulsoo") =
        postTransfer("""{"fromAccountId":"$from","toAccountId":"$to","amount":$amount}""", key, user)

    // ───────────── 정상 ─────────────

    @Test
    fun `송금 완료 - 201, Location으로 다시 조회할 수 있다`() {
        val response = postTransfer(from, to, 10_000).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.amount") { value(10_000) }
            jsonPath("$.coreEntryId") { exists() }
        }.andReturn().response

        val location = response.getHeader("Location")!!
        val transferId = JsonPath.read<String>(response.contentAsString, "$.transferId")
        assertTrue(location.endsWith("/transfers/$transferId"))

        mockMvc.get(location).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED") }
        }
    }

    @Test
    fun `core 거절 - 그래도 201 (송금 기록은 생겼다), 결과는 body의 status와 failureCode`() {
        fakeCore.respond = { CoreTransferResult.Rejected("INSUFFICIENT_BALANCE") }

        postTransfer(from, to, 10_000).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.failureCode") { value("INSUFFICIENT_BALANCE") }
        }
    }

    @Test
    fun `core 결과 불명 - 201 + UNKNOWN, 클라이언트는 Location으로 나중에 다시 확인한다`() {
        fakeCore.respond = { CoreTransferResult.Unknown("read timeout") }

        postTransfer(from, to, 10_000).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("UNKNOWN") }
        }
    }

    @Test
    fun `없는 송금 조회 - 404`() {
        mockMvc.get("/transfers/${UUID.randomUUID()}").andExpect { status { isNotFound() } }
    }

    // ───────────── 400: 고객이 고칠 수 있는 실수 ─────────────

    @Test
    fun `금액 0원 - 400 INVALID_AMOUNT, core를 부르지 않는다`() {
        postTransfer(from, to, 0).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_AMOUNT") }
        }
        assertTrue(fakeCore.calls.isEmpty())
    }

    @Test
    fun `음수 금액 - 400`() {
        postTransfer(from, to, -10_000).andExpect { status { isBadRequest() } }
        assertTrue(fakeCore.calls.isEmpty())
    }

    @Test
    fun `같은 계좌로 송금 - 400, core를 부르지 않는다`() {
        postTransfer(from, from, 10_000).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("SAME_ACCOUNT_TRANSFER") }
        }
        assertTrue(fakeCore.calls.isEmpty())
    }

    @Test
    fun `필수 필드 누락 - 400 (500이 아니다)`() {
        postTransfer("""{"fromAccountId":"$from","amount":10000}""").andExpect { status { isBadRequest() } }
        assertTrue(fakeCore.calls.isEmpty())
    }

    @Test
    fun `깨진 JSON - 400`() {
        postTransfer("""{"fromAccountId":""").andExpect { status { isBadRequest() } }
    }

    // ───────────── 고객 멱등 키 (T-2) ─────────────

    @Test
    fun `X-User-Id 없음 - 401 MISSING_USER, core를 부르지 않는다 (gateway를 거치지 않은 호출)`() {
        postTransfer(from, to, 10_000, user = null).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("MISSING_USER") }
        }
        assertTrue(fakeCore.calls.isEmpty())
    }

    @Test
    fun `멱등 키 헤더 없음 - 400 INVALID_IDEMPOTENCY_KEY, core를 부르지 않는다`() {
        postTransfer(from, to, 10_000, key = null).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_IDEMPOTENCY_KEY") }
        }
        assertTrue(fakeCore.calls.isEmpty())
    }

    @Test
    fun `멱등 키 65자 - 400 (DB 컬럼 64자를 넘으면 INSERT에서 터지기 전에 막는다)`() {
        postTransfer(from, to, 10_000, key = "k".repeat(65)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_IDEMPOTENCY_KEY") }
        }
    }

    @Test
    fun `같은 키 재요청 - 200, 같은 송금 ID`() {
        val key = newKey()
        val first = postTransfer(from, to, 10_000, key).andExpect { status { isCreated() } }.andReturn().response
        val firstId = JsonPath.read<String>(first.contentAsString, "$.transferId")

        postTransfer(from, to, 10_000, key).andExpect {
            status { isOk() }
            jsonPath("$.transferId") { value(firstId) }
            jsonPath("$.status") { value("COMPLETED") }
        }
        assertEquals(1, fakeCore.calls.size)
    }

    @Test
    fun `같은 키에 다른 금액 - 409 IDEMPOTENCY_CONFLICT`() {
        val key = newKey()
        postTransfer(from, to, 10_000, key).andExpect { status { isCreated() } }

        postTransfer(from, to, 20_000, key).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
        }
    }
}
