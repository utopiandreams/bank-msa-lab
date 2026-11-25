package lab.bank.transfer.infrastructure.bankmock

import lab.bank.transfer.application.port.out.ExternalBankPort
import lab.bank.transfer.application.port.out.ExternalTransferCommand
import lab.bank.transfer.application.port.out.ExternalTransferLookupResult
import lab.bank.transfer.application.port.out.ExternalTransferResult
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse
import java.util.UUID

/**
 * ExternalBankPort 구현: bank-mock에 이체를 보내고 응답을 셋으로 분류한다.
 *
 * 분류 (bank-mock은 판정이 확실한 거절을 200 + REJECTED로 준다, ADR-0004 결정 8):
 *   200 + ACCEPTED                → Accepted
 *   200 + REJECTED                → Rejected(사유)
 *   400                           → Rejected("EXTERNAL_BAD_REQUEST") + 에러 로그: 외부가 요청을 읽지도 않았으니 처리 안 됨이 확실. 우리 버그 신호
 *   200인데 outcome을 모름/못 읽음 → Unknown: 판정은 났을 수 있다
 *   409 (같은 키에 다른 내용)      → Unknown: 같은 송금 ID로 이미 뭔가 처리됐다 = "안 됨"이 아님
 *   5xx·타임아웃·연결 실패         → Unknown
 */
@Component
class BankMockAdapter(
    @Qualifier("bankMockRestClient") private val bankMockRestClient: RestClient,
) : ExternalBankPort {

    override fun send(command: ExternalTransferCommand): ExternalTransferResult =
        try {
            bankMockRestClient.post()
                .uri("/interbank/transfers")
                .header(IDEMPOTENCY_KEY_HEADER, command.transferId.toString())   // 송금 ID = 외부 멱등 키
                .contentType(MediaType.APPLICATION_JSON)
                .body(BankMockTransferRequest(command.toBankCode, command.toAccountNumber, command.amount.value))
                .exchange { _, response -> classify(command, response) }
        } catch (e: ResourceAccessException) {
            unknown(command, "응답 없음: ${e.message ?: e.javaClass.simpleName}")
        }

    /**
     * 조회 분류 (8주 차 2부). 확실한 답(200 + 아는 status)만 Accepted·Rejected·NotFound, 나머지는 전부 Unknown.
     * 특히 404는 NotFound가 **아니다**: bank-mock은 "없음"을 200 + NOT_FOUND로 준다. 404는 URL·라우팅 문제일 수 있고,
     * 그걸 "없음"으로 읽으면 성공한 이체를 취소한다 (이중 지급)
     */
    override fun lookup(transferId: UUID): ExternalTransferLookupResult =
        try {
            bankMockRestClient.get()
                .uri("/interbank/transfers/{key}", transferId.toString())
                .accept(MediaType.APPLICATION_JSON)
                .exchange { _, response -> classifyLookup(transferId, response) }
        } catch (e: ResourceAccessException) {
            lookupUnknown(transferId, "응답 없음: ${e.message ?: e.javaClass.simpleName}")
        }

    private fun classifyLookup(transferId: UUID, response: ConvertibleClientHttpResponse): ExternalTransferLookupResult {
        val status = response.statusCode.value()
        if (status != 200) return lookupUnknown(transferId, "외부 조회 응답 $status")
        val body = runCatching { response.bodyTo(BankMockLookupResponse::class.java) }.getOrNull()
        return when (body?.status) {
            "ACCEPTED" -> ExternalTransferLookupResult.Accepted
            "REJECTED" -> ExternalTransferLookupResult.Rejected(body.rejectReason ?: "EXTERNAL_REJECTED")
            "NOT_FOUND" -> ExternalTransferLookupResult.NotFound
            else -> lookupUnknown(transferId, "200 응답인데 조회 결과를 읽을 수 없음: ${body?.status}")
        }
    }

    // 조회 실패는 아무것도 바꾸지 않고 다음 회차에 다시 묻는다 → warn (송금 결과 불명처럼 사람이 볼 일은 아님)
    private fun lookupUnknown(transferId: UUID, reason: String): ExternalTransferLookupResult.Unknown {
        log.warn("외부 이체 조회 실패 transferId={} reason={}", transferId, reason)
        return ExternalTransferLookupResult.Unknown(reason)
    }

    private fun classify(command: ExternalTransferCommand, response: ConvertibleClientHttpResponse): ExternalTransferResult {
        val status = response.statusCode.value()
        return when (status) {
            200 -> {
                val body = runCatching { response.bodyTo(BankMockTransferResponse::class.java) }.getOrNull()
                when (body?.outcome) {
                    "ACCEPTED" -> ExternalTransferResult.Accepted
                    "REJECTED" -> {
                        val reason = body.rejectReason ?: "EXTERNAL_REJECTED"
                        log.info("외부 은행이 이체를 거절 transferId={} reason={}", command.transferId, reason)
                        ExternalTransferResult.Rejected(reason)
                    }
                    else -> unknown(command, "200 응답인데 판정을 읽을 수 없음: ${body?.outcome}")
                }
            }
            400 -> {
                log.error("외부 은행이 요청 형식을 거절 transferId={} (우리 쪽 버그 신호)", command.transferId)
                ExternalTransferResult.Rejected("EXTERNAL_BAD_REQUEST")
            }
            else -> unknown(command, "외부 응답 $status")
        }
    }

    // 결과 불명은 보상하지 않고 남겨 두는 상태라, 사람이(8주 차엔 해소 스케줄러가) 추적할 수 있게 반드시 에러 로그
    private fun unknown(command: ExternalTransferCommand, reason: String): ExternalTransferResult.Unknown {
        log.error("외부 이체 결과 불명 transferId={} reason={}", command.transferId, reason)
        return ExternalTransferResult.Unknown(reason)
    }

    companion object {
        private val log = LoggerFactory.getLogger(BankMockAdapter::class.java)
        private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
    }
}
