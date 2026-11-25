package lab.bank.transfer.infrastructure.core

import lab.bank.transfer.application.port.out.CoreTransferLookupResult
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse
import java.util.UUID

/**
 * core 상태 조회 GET /postings/{송금 ID}와 응답 분류. 당행 송금(CorePostingAdapter)과 타행 출금(CoreInterbankAdapter)이 같이 쓴다:
 * core는 거래 종류와 상관없이 멱등 키(= 송금 ID)로 전표를 찾으므로 계약이 하나다.
 *
 *   200 + COMPLETED(전표 ID 있음) → Completed
 *   200 + NOT_PROCESSED            → NotProcessed (지금 이 순간 전표 없음. 믿어도 되는 시점인지는 유스케이스가 판단)
 *   그 밖(응답 없음, 200 아님, 해석 불가) → Unknown
 */
object CorePostingLookup {

    private val log = LoggerFactory.getLogger(CorePostingLookup::class.java)

    fun lookup(coreRestClient: RestClient, transferId: UUID, what: String): CoreTransferLookupResult =
        try {
            coreRestClient.get()
                .uri("/postings/{key}", transferId.toString())
                .accept(MediaType.APPLICATION_JSON)
                .exchange { _, response -> classify(transferId, what, response) }
        } catch (e: ResourceAccessException) {
            log.warn("core {} 조회 응답 없음 transferId={} cause={}", what, transferId, e.message)
            CoreTransferLookupResult.Unknown(e.message ?: e.javaClass.simpleName)
        }

    private fun classify(transferId: UUID, what: String, response: ConvertibleClientHttpResponse): CoreTransferLookupResult {
        val status = response.statusCode.value()
        if (status != 200) {
            log.warn("core {} 조회 실패 transferId={} status={}", what, transferId, status)
            return CoreTransferLookupResult.Unknown("core 조회 응답 $status")
        }
        val body = runCatching { response.bodyTo(CorePostingStatusResponse::class.java) }.getOrNull()
        return when {
            body?.status == "COMPLETED" && body.entryId != null -> CoreTransferLookupResult.Completed(body.entryId)
            body?.status == "NOT_PROCESSED" -> CoreTransferLookupResult.NotProcessed
            else -> {
                log.warn("core {} 조회 응답을 해석할 수 없음 transferId={} body={}", what, transferId, body)
                CoreTransferLookupResult.Unknown("core 조회 응답 해석 불가: $body")
            }
        }
    }
}
