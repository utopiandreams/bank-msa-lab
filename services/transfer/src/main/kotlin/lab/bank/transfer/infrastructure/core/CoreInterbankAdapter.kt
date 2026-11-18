package lab.bank.transfer.infrastructure.core

import lab.bank.transfer.application.port.out.CoreInterbankPort
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.InterbankWithdrawCommand
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse
import java.util.UUID

/**
 * CoreInterbankPort 구현: core 타행 출금·출금 취소 (ADR-0004, core S-2 API). 분류 기준은 포트 주석.
 * 같은 core·같은 RestClient(타임아웃 설정)를 쓴다. 출금 분류는 당행 송금(CorePostingAdapter)과 같은 표다.
 */
@Component
class CoreInterbankAdapter(
    @Qualifier("coreRestClient") private val coreRestClient: RestClient,
) : CoreInterbankPort {

    override fun withdraw(command: InterbankWithdrawCommand): CoreTransferResult =
        call("withdraw", command.transferId) {
            coreRestClient.post()
                .uri("/postings/interbank-withdrawals")
                .header(IDEMPOTENCY_KEY_HEADER, command.transferId.toString())   // 송금 ID = core 멱등 키 (취소 때 이 키로 가리킨다)
                .header(USER_ID_HEADER, command.requesterId.value)              // core가 출금 계좌 주인을 검사 (BOLA)
                .contentType(MediaType.APPLICATION_JSON)
                .body(CoreInterbankWithdrawalRequest(command.fromAccountId, command.amount.value))
                .exchange { _, response -> classifyWithdraw(command.transferId, response) }
        }

    override fun cancelWithdraw(transferId: UUID): CoreTransferResult =
        call("cancel", transferId) {
            // 바디·멱등 키 헤더 없음: 원래 출금(송금 ID)을 가리키면 core가 그 전표를 정확히 되돌리고, 두 번 취소를 원장 제약으로 막는다
            coreRestClient.post()
                .uri("/postings/interbank-withdrawals/{key}/cancel", transferId.toString())
                .exchange { _, response -> classifyCancel(transferId, response) }
        }

    // 응답을 못 받음(연결 실패·타임아웃) → 처리됐는지 모름
    private fun call(op: String, transferId: UUID, block: () -> CoreTransferResult): CoreTransferResult =
        try {
            block()
        } catch (e: ResourceAccessException) {
            log.warn("core 타행 {} 응답 없음 transferId={} cause={}", op, transferId, e.message)
            CoreTransferResult.Unknown(e.message ?: e.javaClass.simpleName)
        }

    // CorePostingAdapter.classify와 같은 표: 2xx 완료 / 400·404·422 거절(롤백 확실) / 409·그 밖 모름
    private fun classifyWithdraw(transferId: UUID, response: ConvertibleClientHttpResponse): CoreTransferResult {
        val status = response.statusCode.value()
        return when (status) {
            201, 200 -> completedOrUnknown(transferId, response, "withdraw")
            400, 404, 422 -> {
                val code = response.problemOrNull()?.code ?: "CORE_HTTP_$status"
                if (status == 400) log.error("core가 타행 출금을 입력 오류로 거절 transferId={} code={}", transferId, code)
                else log.info("core가 타행 출금을 거절 transferId={} code={}", transferId, code)
                CoreTransferResult.Rejected(code)
            }
            else -> unknown(transferId, "withdraw", "core 응답 $status ${response.problemOrNull()?.detail.orEmpty()}".trim())
        }
    }

    // 취소에는 Rejected가 없다 (포트 주석): 2xx만 완료, 나머지는 전부 모름 → COMPENSATING에 남는다
    private fun classifyCancel(transferId: UUID, response: ConvertibleClientHttpResponse): CoreTransferResult {
        val status = response.statusCode.value()
        return when (status) {
            201, 200 -> completedOrUnknown(transferId, response, "cancel")
            else -> {
                val problem = response.problemOrNull()
                unknown(transferId, "cancel", "core 응답 $status ${problem?.code.orEmpty()} ${problem?.detail.orEmpty()}".trim())
            }
        }
    }

    // 2xx인데 body를 못 읽으면: 커밋은 됐을 수 있다 → 실패가 아니라 모름
    private fun completedOrUnknown(transferId: UUID, response: ConvertibleClientHttpResponse, op: String): CoreTransferResult =
        runCatching { response.bodyTo(CorePostingResponse::class.java) }.getOrNull()
            ?.let { CoreTransferResult.Completed(it.entryId) }
            ?: unknown(transferId, op, "core ${response.statusCode} 응답인데 body를 읽을 수 없음")

    private fun ConvertibleClientHttpResponse.problemOrNull(): CoreProblem? =
        runCatching { bodyTo(CoreProblem::class.java) }.getOrNull()

    private fun unknown(transferId: UUID, op: String, reason: String): CoreTransferResult.Unknown {
        log.error("core 타행 {} 결과 불명 transferId={} reason={}", op, transferId, reason)
        return CoreTransferResult.Unknown(reason)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CoreInterbankAdapter::class.java)
        private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        private const val USER_ID_HEADER = "X-User-Id"
    }
}
