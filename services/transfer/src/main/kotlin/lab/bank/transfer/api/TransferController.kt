package lab.bank.transfer.api

import lab.bank.transfer.application.TransferQuery
import lab.bank.transfer.application.TransferUseCase
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.UserId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

/**
 * 송금 API.
 *
 * POST 응답 코드:
 *   처음 요청 → 결과와 상관없이 201 + Location. 송금 기록(자원)은 COMPLETED든 FAILED든 UNKNOWN이든 만들어졌으므로.
 *   같은 멱등 키 재요청 → 200 + 기존 송금의 현재 상태 (T-2)
 *   결과는 body의 status로 구분한다. 끝 상태가 아니면(PROCESSING, UNKNOWN) 클라이언트는 Location(GET)으로 다시 확인한다.
 */
@RestController
@RequestMapping("/transfers")
class TransferController(
    private val transferUseCase: TransferUseCase,
    private val transferQuery: TransferQuery,
) {

    // 헤더를 required = false로 받고 직접 검사한다: Spring 기본 400(MissingRequestHeaderException)은 code가 없어서
    // 다른 400들과 응답 형식(ProblemDetail + code)을 맞추려고
    @PostMapping
    fun create(
        @RequestHeader(USER_ID_HEADER, required = false) userId: String?,   // gateway가 토큰의 sub로 덮어쓴 값 (ADR-0002)
        @RequestHeader(IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        @RequestBody request: CreateTransferRequest,
    ): ResponseEntity<TransferResponse> {
        // 원시 값 → 값 객체 변환이 곧 검증. 실패하면 InvalidIdempotencyKeyException / InvalidAmountException → 400
        val result = transferUseCase.transfer(
            UserId.fromHeader(userId),   // 없으면 MissingUserException → 401 (멱등 키 검사보다 먼저: 누구인지가 먼저)
            IdempotencyKey.fromHeader(idempotencyKey),
            request.fromAccountId,
            request.toAccountId,
            Amount(request.amount),
        )
        val body = TransferResponse.from(result.transfer)
        // 재요청 → 200 (아무것도 새로 만들지 않았으므로, core와 같은 규칙). 처음 → 201 + Location
        if (result.replayed) return ResponseEntity.ok(body)
        val location = UriComponentsBuilder.fromPath("/transfers/{id}").buildAndExpand(result.transfer.id).toUri()
        return ResponseEntity.created(location).body(body)
    }

    companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        const val USER_ID_HEADER = "X-User-Id"
    }

    // 송금은 우리가 발급한 ID로 찾는 자원이라 없으면 404 (core 상태 조회는 "처리 안 됨"이 정상 답이라 200이었던 것과 다르다)
    @GetMapping("/{transferId}")
    fun get(@PathVariable transferId: UUID): ResponseEntity<TransferResponse> =
        transferQuery.find(transferId)
            ?.let { ResponseEntity.ok(TransferResponse.from(it)) }
            ?: ResponseEntity.notFound().build()
}
