package lab.bank.core.posting.api

import com.jayway.jsonpath.JsonPath
import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import lab.bank.core.posting.application.PostingUseCase
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
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

// HTTP 계층 테스트: 상태 코드·헤더·예외 매핑을 실제 요청으로 확인한다 (업무 규칙 자체는 PostingUseCaseTest가 검사)
// 핵심 확인: 고객이 고칠 수 있는 실수는 전부 4xx이고, 500이 나는 경로가 없다
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PostingControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var accountUseCase: AccountUseCase
    @Autowired lateinit var postingUseCase: PostingUseCase

    private fun newAccount(): UUID = accountUseCase.create(UserId("u-1")).id
    private fun newKey() = "key-${UUID.randomUUID()}"
    private fun fundedAccount(amount: Long): UUID =
        newAccount().also { postingUseCase.deposit(IdempotencyKey(newKey()), it, Amount(amount)) }

    private fun deposit(key: String?, accountId: UUID, amount: Long): ResultActionsDsl =
        mockMvc.post("/postings/deposits") {
            key?.let { header("Idempotency-Key", it) }
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountId":"$accountId","amount":$amount}"""
        }

    private fun withdraw(key: String, accountId: UUID, amount: Long): ResultActionsDsl =
        mockMvc.post("/postings/withdrawals") {
            header("Idempotency-Key", key)
            contentType = MediaType.APPLICATION_JSON
            content = """{"accountId":"$accountId","amount":$amount}"""
        }

    // user: transfer가 전파하는 X-User-Id. null이면 헤더를 안 보낸다
    private fun transfer(key: String, from: UUID, to: UUID, amount: Long, user: String? = "u-1"): ResultActionsDsl =
        mockMvc.post("/postings/transfers") {
            header("Idempotency-Key", key)
            user?.let { header("X-User-Id", it) }
            contentType = MediaType.APPLICATION_JSON
            content = """{"fromAccountId":"$from","toAccountId":"$to","amount":$amount}"""
        }

    // ───────────── 정상 / 멱등 ─────────────

    @Test
    fun `처음 처리하면 201과 조회 URL, 같은 키로 다시 보내면 200과 같은 거래 ID`() {
        val accountId = newAccount()
        val key = newKey()

        val first = deposit(key, accountId, 10_000).andExpect {
            status { isCreated() }
            header { string("Location", "/postings/$key") }
            jsonPath("$.replayed") { value(false) }
        }.andReturn().response.contentAsString
        val entryId = JsonPath.read<String>(first, "$.entryId")

        deposit(key, accountId, 10_000).andExpect {
            status { isOk() }   // 아무것도 새로 만들지 않았다
            jsonPath("$.entryId") { value(entryId) }
            jsonPath("$.replayed") { value(true) }
        }
    }

    @Test
    fun `송금하면 201`() {
        transfer(newKey(), fundedAccount(10_000), newAccount(), 3_000).andExpect {
            status { isCreated() }
        }
    }

    // ───────────── 상태 조회 ─────────────

    @Test
    fun `처리된 키를 조회하면 COMPLETED와 거래 정보`() {
        val key = newKey()
        val entryId = postingUseCase.deposit(IdempotencyKey(key), newAccount(), Amount(10_000)).entryId

        mockMvc.get("/postings/$key").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.entryId") { value(entryId.toString()) }
            jsonPath("$.type") { value("DEPOSIT") }
        }
    }

    @Test
    fun `처리된 적 없는 키를 조회하면 404가 아니라 200과 NOT_PROCESSED`() {
        mockMvc.get("/postings/${newKey()}").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("NOT_PROCESSED") }
            jsonPath("$.entryId") { doesNotExist() }
        }
    }

    // ───────────── 고객이 고칠 수 있는 실수 → 4xx (500이 아니다) ─────────────

    @Test
    fun `멱등 키 헤더가 없으면 400`() {
        deposit(null, newAccount(), 10_000).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `0원이면 400 INVALID_AMOUNT`() {
        // 예전엔 원장의 require에 먼저 걸려 500이었다 → 컨트롤러의 값 객체 변환에서 400으로 거절
        deposit(newKey(), newAccount(), 0).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_AMOUNT") }
        }
    }

    @Test
    fun `멱등 키가 64자를 넘으면 400 INVALID_IDEMPOTENCY_KEY`() {
        deposit("k".repeat(65), newAccount(), 10_000).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_IDEMPOTENCY_KEY") }
        }
    }

    @Test
    fun `같은 계좌로 송금하면 400 SAME_ACCOUNT_TRANSFER`() {
        val accountId = fundedAccount(10_000)
        transfer(newKey(), accountId, accountId, 3_000).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("SAME_ACCOUNT_TRANSFER") }
        }
    }

    @Test
    fun `송금에 X-User-Id가 없으면 401 MISSING_USER`() {
        transfer(newKey(), fundedAccount(10_000), newAccount(), 3_000, user = null).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("MISSING_USER") }
        }
    }

    @Test
    fun `남의 계좌에서 송금하면 404 ACCOUNT_NOT_FOUND - 없는 계좌와 같은 응답`() {
        transfer(newKey(), fundedAccount(10_000), newAccount(), 3_000, user = "younghee").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `없는 계좌에 입금하면 404 ACCOUNT_NOT_FOUND`() {
        deposit(newKey(), UUID.randomUUID(), 10_000).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `같은 키에 다른 금액이면 409 IDEMPOTENCY_CONFLICT`() {
        val accountId = newAccount()
        val key = newKey()
        deposit(key, accountId, 10_000)

        deposit(key, accountId, 20_000).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
        }
    }

    @Test
    fun `잔액보다 많이 출금하면 422 INSUFFICIENT_BALANCE`() {
        // AccountExceptionHandler가 전역이어야 거래 API에서도 422가 된다 (패키지 한정이었으면 500)
        withdraw(newKey(), fundedAccount(1_000), 3_000).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("INSUFFICIENT_BALANCE") }
        }
    }
}
