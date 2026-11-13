package lab.bank.transfer.infrastructure.core

import lab.bank.transfer.application.port.out.CorePostingPort
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.domain.Transfer
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse

/**
 * CorePostingPort 구현: core에 당행 송금을 요청하고, 응답을 CoreTransferResult로 번역한다.
 *
 * 예외를 밖으로 던지지 않는다 (포트 계약). 응답이 오면 classify가, 응답이 안 오면 catch가 결과를 정한다.
 */
@Component
class CorePostingAdapter(
    private val coreRestClient: RestClient,
) : CorePostingPort {

    override fun transfer(transfer: Transfer): CoreTransferResult =
        try {
            coreRestClient.post()
                .uri("/postings/transfers")
                .header(IDEMPOTENCY_KEY_HEADER, transfer.id.toString())   // 송금 ID = core 쪽 멱등 키
                .contentType(MediaType.APPLICATION_JSON)
                .body(CoreTransferRequest(transfer.fromAccountId, transfer.toAccountId, transfer.amount.value))
                // exchange: 4xx·5xx도 예외로 바꾸지 않고 응답을 그대로 받는다 → 분류를 classify 한곳에서 한다
                .exchange { _, response -> classify(transfer, response) }
        } catch (e: ResourceAccessException) {
            // 응답 자체를 못 받음: 연결 실패, connect/read 타임아웃 등 I/O 오류
            // 요청이 core에 닿았는지, 닿았다면 커밋했는지 모른다 → Unknown (분류표 합의, 2026-11-13)
            log.warn("core 송금 요청 응답 없음 transferId={} cause={}", transfer.id, e.message)
            CoreTransferResult.Unknown(e.message ?: e.javaClass.simpleName)
        }

    /*
    응답을 받았을 때의 분류 (응답을 못 받은 타임아웃·연결 실패는 위 catch에서 UNKNOWN)
    201 - 처음 처리 (COMPLETED)
    200 - 같은 키 재요청, 이미 처리됨 (COMPLETED)
    400 - 입력값 오류 (REJECTED) + 에러 로그: use case 입구에서 걸렀어야 하므로 우리 버그 신호
    404 - 계좌 없음 (REJECTED), 422 - 잔액 부족 (REJECTED): core의 업무 거절, 트랜잭션 롤백으로 처리 안 됨이 확실
    409 - 멱등 충돌 (UNKNOWN): 같은 송금 ID로 다른 거래가 이미 있다 → "처리 안 됨"이 아님
    그 밖 (5xx 등) - UNKNOWN: core가 커밋한 뒤 응답 중에 터졌을 수 있음
     */
    private fun classify(
        transfer: Transfer,
        response: ConvertibleClientHttpResponse,
    ): CoreTransferResult {
        val status: HttpStatusCode = response.statusCode
        return when (status.value()) {
            201, 200 ->
                runCatching { response.bodyTo(CorePostingResponse::class.java) }.getOrNull()
                    ?.let { CoreTransferResult.Completed(it.entryId) }
                    // 커밋은 됐는데 원장 ID를 못 읽음 → FAILED로 보내면 "돈은 나갔는데 실패" → UNKNOWN, 상태 조회로 확정
                    ?: unknown(transfer, "core $status 응답인데 body를 읽을 수 없음")

            400, 404, 422 -> {
                val code = response.problemOrNull()?.code ?: "CORE_HTTP_${status.value()}"
                if (status.value() == 400) {
                    log.error("core가 송금 요청을 입력 오류로 거절 transferId={} code={}", transfer.id, code)
                } else {
                    log.info("core가 송금을 거절 transferId={} code={}", transfer.id, code)
                }
                CoreTransferResult.Rejected(code)
            }

            409 -> unknown(transfer, "core 멱등 충돌: ${response.problemOrNull()?.detail ?: status}")

            else -> unknown(transfer, "core 응답 $status ${response.problemOrNull()?.detail.orEmpty()}".trim())
        }
    }

    // 에러 body(ProblemDetail)를 읽는다. 비었거나 형식이 다르면 null (분류는 상태 코드로 이미 정해졌으므로 body는 부가 정보)
    private fun ConvertibleClientHttpResponse.problemOrNull(): CoreProblem? =
        runCatching { bodyTo(CoreProblem::class.java) }.getOrNull()

    // UNKNOWN은 사람이(8주 차엔 해소 스케줄러가) 나중에 추적해야 하는 상태라 반드시 에러 로그를 남긴다
    private fun unknown(transfer: Transfer, reason: String): CoreTransferResult.Unknown {
        log.error("core 송금 결과 불명 transferId={} reason={}", transfer.id, reason)
        return CoreTransferResult.Unknown(reason)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CorePostingAdapter::class.java)
        private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
    }
}
