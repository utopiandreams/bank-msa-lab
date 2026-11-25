package lab.bank.transfer.infrastructure.bankmock

import lab.bank.transfer.application.port.out.ExternalTransferCommand
import lab.bank.transfer.application.port.out.ExternalTransferLookupResult
import lab.bank.transfer.application.port.out.ExternalTransferResult
import lab.bank.transfer.domain.Amount
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

// 외부 결제망 응답 분류. 핵심: 확실한 거절만 Rejected(→ 보상), 애매하면 Unknown(→ 보상 금지, ADR-0004)
class BankMockAdapterTest {

    private val builder = RestClient.builder().baseUrl("http://bank-mock")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val adapter = BankMockAdapter(builder.build())
    private val command = ExternalTransferCommand(UUID.randomUUID(), "088", "1234567890", Amount(10_000))

    private fun respond(status: HttpStatus, body: String = "") {
        server.expect(requestTo("http://bank-mock/interbank/transfers"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Idempotency-Key", command.transferId.toString()))
            .andExpect(jsonPath("$.toAccountNumber").value("1234567890"))
            .andExpect(jsonPath("$.amount").value(10_000))
            .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body))
    }

    @Test
    fun `200 ACCEPTED는 Accepted - 피벗 통과`() {
        respond(HttpStatus.OK, """{"idempotencyKey":"x","outcome":"ACCEPTED","rejectReason":null}""")
        assertEquals(ExternalTransferResult.Accepted, adapter.send(command))
    }

    @Test
    fun `200 REJECTED는 Rejected와 사유 - 보상 대상`() {
        respond(HttpStatus.OK, """{"idempotencyKey":"x","outcome":"REJECTED","rejectReason":"ACCOUNT_NOT_FOUND"}""")
        assertEquals(ExternalTransferResult.Rejected("ACCOUNT_NOT_FOUND"), adapter.send(command))
    }

    @Test
    fun `200인데 모르는 판정이면 Unknown - 판정은 났을 수 있다`() {
        respond(HttpStatus.OK, """{"idempotencyKey":"x","outcome":"PENDING"}""")
        assertIs<ExternalTransferResult.Unknown>(adapter.send(command))
    }

    @Test
    fun `400은 Rejected - 외부가 요청을 읽지도 않았다`() {
        respond(HttpStatus.BAD_REQUEST)
        assertEquals(ExternalTransferResult.Rejected("EXTERNAL_BAD_REQUEST"), adapter.send(command))
    }

    @Test
    fun `409는 Unknown - 같은 송금 ID로 이미 뭔가 처리됐다`() {
        respond(HttpStatus.CONFLICT)
        assertIs<ExternalTransferResult.Unknown>(adapter.send(command))
    }

    @Test
    fun `5xx는 Unknown - 보상하면 안 된다`() {
        respond(HttpStatus.SERVICE_UNAVAILABLE)
        assertIs<ExternalTransferResult.Unknown>(adapter.send(command))
    }

    // ---- 조회 (8주 차 2부): 확실한 답만 Accepted·Rejected·NotFound, 나머지는 Unknown ----

    private fun respondLookup(status: HttpStatus, body: String = "") {
        server.expect(requestTo("http://bank-mock/interbank/transfers/${command.transferId}"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body))
    }

    @Test
    fun `조회 200 ACCEPTED는 Accepted`() {
        respondLookup(HttpStatus.OK, """{"idempotencyKey":"x","status":"ACCEPTED","rejectReason":null}""")
        assertEquals(ExternalTransferLookupResult.Accepted, adapter.lookup(command.transferId))
    }

    @Test
    fun `조회 200 REJECTED는 Rejected와 사유`() {
        respondLookup(HttpStatus.OK, """{"idempotencyKey":"x","status":"REJECTED","rejectReason":"RECEIVING_BANK_REJECTED"}""")
        assertEquals(ExternalTransferLookupResult.Rejected("RECEIVING_BANK_REJECTED"), adapter.lookup(command.transferId))
    }

    @Test
    fun `조회 200 NOT_FOUND는 NotFound`() {
        respondLookup(HttpStatus.OK, """{"idempotencyKey":"x","status":"NOT_FOUND","rejectReason":null}""")
        assertEquals(ExternalTransferLookupResult.NotFound, adapter.lookup(command.transferId))
    }

    @Test
    fun `조회 404는 NotFound가 아니라 Unknown - 라우팅 문제를 '없음'으로 읽으면 성공한 이체를 취소한다`() {
        respondLookup(HttpStatus.NOT_FOUND)
        assertIs<ExternalTransferLookupResult.Unknown>(adapter.lookup(command.transferId))
    }

    @Test
    fun `조회 5xx·모르는 status는 Unknown`() {
        respondLookup(HttpStatus.SERVICE_UNAVAILABLE)
        assertIs<ExternalTransferLookupResult.Unknown>(adapter.lookup(command.transferId))
        server.reset()
        respondLookup(HttpStatus.OK, """{"idempotencyKey":"x","status":"PENDING","rejectReason":null}""")
        assertIs<ExternalTransferLookupResult.Unknown>(adapter.lookup(command.transferId))
    }
}
