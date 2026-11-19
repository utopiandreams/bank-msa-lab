package lab.bank.transfer.api

import com.jayway.jsonpath.JsonPath
import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.FakeCoreInterbankPort
import lab.bank.transfer.FakeCorePostingPort
import lab.bank.transfer.FakeExternalBankPort
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.ExternalTransferResult
import org.hamcrest.Matchers.containsString
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
import kotlin.test.assertTrue

// 타행 송금 HTTP 계층: toBankCode 분기, 상태 코드, 요청 조합 검사, 당행·타행 멱등 키 겹침 (사가 흐름 자체는 InterbankTransferUseCaseTest)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class InterbankTransferControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var core: FakeCorePostingPort
    @Autowired lateinit var coreInterbank: FakeCoreInterbankPort
    @Autowired lateinit var externalBank: FakeExternalBankPort

    private val from = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        core.reset()
        coreInterbank.reset()
        externalBank.reset()
    }

    private fun newKey() = "key-${UUID.randomUUID()}"

    private fun post(body: String, key: String = newKey()): ResultActionsDsl =
        mockMvc.post("/transfers") {
            header("Idempotency-Key", key)
            header("X-User-Id", "chulsoo")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun interbank(bankCode: String = "004", accountNumber: String = "123-456", amount: Long = 10_000) =
        """{"fromAccountId":"$from","toBankCode":"$bankCode","toAccountNumber":"$accountNumber","amount":$amount}"""

    private fun domestic(to: UUID = UUID.randomUUID(), amount: Long = 10_000) =
        """{"fromAccountId":"$from","toAccountId":"$to","amount":$amount}"""

    // ───────────── 정상 ─────────────

    @Test
    fun `타행 송금 성공 - 201, 받는 쪽은 은행 코드·계좌번호, Location으로 다시 조회된다`() {
        val response = post(interbank()).andExpect {
            status { isCreated() }
            jsonPath("$.kind") { value("INTERBANK") }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.toBankCode") { value("004") }
            jsonPath("$.toAccountNumber") { value("123-456") }
            jsonPath("$.toAccountId") { doesNotExist() }
        }.andReturn().response

        mockMvc.get(response.getHeader("Location")!!).andExpect {
            status { isOk() }
            jsonPath("$.kind") { value("INTERBANK") }
            jsonPath("$.status") { value("COMPLETED") }
        }
        assertTrue(core.calls.isEmpty())   // 당행 경로는 타지 않는다
    }

    @Test
    fun `외부 거절 → 보상 - 201, CANCELLED, failureCode는 외부 거절 사유`() {
        externalBank.respond = { ExternalTransferResult.Rejected("ACCOUNT_NOT_FOUND") }

        post(interbank()).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("CANCELLED") }
            jsonPath("$.failureCode") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `출금 거절 - 201, FAILED, failureCode는 출금 거절 사유`() {
        coreInterbank.withdrawRespond = { CoreTransferResult.Rejected("INSUFFICIENT_BALANCE") }

        post(interbank()).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.failureCode") { value("INSUFFICIENT_BALANCE") }
        }
    }

    @Test
    fun `우리 은행 코드면 당행으로 간다 - toAccountId로 당행 송금`() {
        post("""{"fromAccountId":"$from","toBankCode":"999","toAccountId":"${UUID.randomUUID()}","amount":10000}""").andExpect {
            status { isCreated() }
            jsonPath("$.kind") { value("DOMESTIC") }
        }
        assertTrue(coreInterbank.withdrawCalls.isEmpty())
    }

    // ───────────── 멱등 ─────────────

    @Test
    fun `같은 키 재요청 - 200, 같은 송금 ID`() {
        val key = newKey()
        val first = post(interbank(), key).andExpect { status { isCreated() } }.andReturn().response
        val firstId = JsonPath.read<String>(first.contentAsString, "$.transferId")

        post(interbank(), key).andExpect {
            status { isOk() }
            jsonPath("$.transferId") { value(firstId) }
        }
    }

    @Test
    fun `같은 키에 다른 계좌번호 - 409`() {
        val key = newKey()
        post(interbank(accountNumber = "123-456"), key).andExpect { status { isCreated() } }

        post(interbank(accountNumber = "999-999"), key).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
        }
    }

    // 같은 테이블이라 조회는 되는데 상태 enum이 달라 매핑이 깨지던 곳: 500이 아니라 원인이 적힌 409
    @Test
    fun `타행에 쓴 키로 당행 송금 - 409, 원인이 문구에 보인다`() {
        val key = newKey()
        post(interbank(), key).andExpect { status { isCreated() } }

        post(domestic(), key).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
            jsonPath("$.detail") { value(containsString("타행 송금에 이미 쓰인")) }
        }
        assertTrue(core.calls.isEmpty())
    }

    @Test
    fun `당행에 쓴 키로 타행 송금 - 409, 원인이 문구에 보인다`() {
        val key = newKey()
        post(domestic(), key).andExpect { status { isCreated() } }

        post(interbank(), key).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
            jsonPath("$.detail") { value(containsString("당행 송금에 이미 쓰인")) }
        }
        assertTrue(coreInterbank.withdrawCalls.isEmpty())
    }

    // ───────────── 400: 고객이 고칠 수 있는 실수, 아무것도 호출하지 않는다 ─────────────

    @Test
    fun `은행 코드 형식이 틀림 - 400 INVALID_BANK_CODE`() {
        post(interbank(bankCode = "abc")).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_BANK_CODE") }
        }
        assertTrue(coreInterbank.withdrawCalls.isEmpty())
    }

    @Test
    fun `타행인데 계좌번호 없음 - 400 INVALID_TRANSFER_REQUEST`() {
        post("""{"fromAccountId":"$from","toBankCode":"004","amount":10000}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_TRANSFER_REQUEST") }
        }
        assertTrue(coreInterbank.withdrawCalls.isEmpty())
    }

    @Test
    fun `당행인데 받는 계좌 ID 없음 - 400 INVALID_TRANSFER_REQUEST`() {
        post("""{"fromAccountId":"$from","amount":10000}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_TRANSFER_REQUEST") }
        }
        assertTrue(core.calls.isEmpty())
    }
}
