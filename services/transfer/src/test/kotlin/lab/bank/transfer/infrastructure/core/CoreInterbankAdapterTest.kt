package lab.bank.transfer.infrastructure.core

import lab.bank.transfer.application.port.out.CoreTransferLookupResult
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.InterbankWithdrawCommand
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.UserId
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

// core 타행 출금·취소 응답 분류 (ADR-0004). 핵심: 취소에는 Rejected가 없다 (보상은 실패로 끝날 수 없다)
class CoreInterbankAdapterTest {

    private val builder = RestClient.builder().baseUrl("http://core")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val adapter = CoreInterbankAdapter(builder.build())

    private val transferId = UUID.randomUUID()
    private val entryId = UUID.randomUUID()
    private val command = InterbankWithdrawCommand(transferId, UserId("chulsoo"), UUID.randomUUID(), Amount(10_000))

    private fun json(status: HttpStatus, body: String, problem: Boolean = false) =
        withStatus(status).contentType(if (problem) MediaType.APPLICATION_PROBLEM_JSON else MediaType.APPLICATION_JSON).body(body)

    private fun problem(status: HttpStatus, code: String) =
        json(status, """{"title":"x","status":${status.value()},"detail":"테스트","code":"$code"}""", problem = true)

    private fun expectWithdraw() = server.expect(requestTo("http://core/postings/interbank-withdrawals"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", transferId.toString()))
        .andExpect(header("X-User-Id", "chulsoo"))
        .andExpect(jsonPath("$.amount").value(10_000))

    private fun expectCancel() = server.expect(requestTo("http://core/postings/interbank-withdrawals/$transferId/cancel"))
        .andExpect(method(HttpMethod.POST))

    @Test
    fun `출금 201은 Completed와 출금 전표 ID`() {
        expectWithdraw().andRespond(json(HttpStatus.CREATED, """{"entryId":"$entryId","replayed":false}"""))
        assertEquals(CoreTransferResult.Completed(entryId), adapter.withdraw(command))
    }

    @Test
    fun `출금 422 잔액 부족은 Rejected - 롤백 확실`() {
        expectWithdraw().andRespond(problem(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_BALANCE"))
        assertEquals(CoreTransferResult.Rejected("INSUFFICIENT_BALANCE"), adapter.withdraw(command))
    }

    @Test
    fun `출금 409·5xx는 Unknown`() {
        expectWithdraw().andRespond(problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"))
        assertIs<CoreTransferResult.Unknown>(adapter.withdraw(command))
    }

    @Test
    fun `취소 201·200은 Completed - 이미 취소돼 있어도 완료`() {
        expectCancel().andRespond(json(HttpStatus.CREATED, """{"entryId":"$entryId","replayed":false}"""))
        expectCancel().andRespond(json(HttpStatus.OK, """{"entryId":"$entryId","replayed":true}"""))
        assertEquals(CoreTransferResult.Completed(entryId), adapter.cancelWithdraw(transferId))
        assertEquals(CoreTransferResult.Completed(entryId), adapter.cancelWithdraw(transferId))
    }

    @Test
    fun `취소 404(원래 출금 없음)는 Rejected가 아니라 Unknown - 보상을 끝난 것으로 보면 안 된다`() {
        expectCancel().andRespond(problem(HttpStatus.NOT_FOUND, "ORIGINAL_ENTRY_NOT_FOUND"))
        assertIs<CoreTransferResult.Unknown>(adapter.cancelWithdraw(transferId))
    }

    @Test
    fun `취소 5xx는 Unknown - COMPENSATING에 남아 재시도`() {
        expectCancel().andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
        assertIs<CoreTransferResult.Unknown>(adapter.cancelWithdraw(transferId))
    }

    // ---- 출금 조회 (8주 차 2부): 당행 송금 조회와 같은 API GET /postings/{송금 ID} ----

    private fun expectLookup() = server.expect(requestTo("http://core/postings/$transferId")).andExpect(method(HttpMethod.GET))

    @Test
    fun `출금 조회 COMPLETED는 Completed와 출금 전표`() {
        expectLookup().andRespond(json(HttpStatus.OK, """{"idempotencyKey":"$transferId","status":"COMPLETED","entryId":"$entryId"}"""))
        assertEquals(CoreTransferLookupResult.Completed(entryId), adapter.lookupWithdraw(transferId))
    }

    @Test
    fun `출금 조회 NOT_PROCESSED는 NotProcessed`() {
        expectLookup().andRespond(json(HttpStatus.OK, """{"idempotencyKey":"$transferId","status":"NOT_PROCESSED"}"""))
        assertEquals(CoreTransferLookupResult.NotProcessed, adapter.lookupWithdraw(transferId))
    }

    @Test
    fun `출금 조회 5xx는 Unknown`() {
        expectLookup().andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
        assertIs<CoreTransferLookupResult.Unknown>(adapter.lookupWithdraw(transferId))
    }
}
