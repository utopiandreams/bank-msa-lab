package lab.bank.core.posting.api

import lab.bank.core.posting.application.PostingResult
import lab.bank.core.posting.application.PostingUseCase
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

/**
 * 거래 API. 명령은 거래 중심(POST /postings/<종류>), 조회는 자원 중심(GET /postings/{멱등 키}).
 *
 * 응답 코드: 처음 처리 → 201 + Location(조회 URL), 같은 키 재요청 → 200 (아무것도 새로 만들지 않았으므로).
 * 호출자는 2xx 전체를 성공으로 보면 된다. 구분이 필요하면 body의 replayed를 본다.
 */
@RestController
@RequestMapping("/postings")
class PostingController(
    private val postingUseCase: PostingUseCase,
) {

    @PostMapping("/deposits")
    fun deposit(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @RequestBody request: DepositRequest,
    ): ResponseEntity<PostingResponse> =
        // 원시 값 → 값 객체 변환이 곧 검증. 실패하면 SharedException → 400
        respond(idempotencyKey, postingUseCase.deposit(IdempotencyKey(idempotencyKey), request.accountId, Amount(request.amount)))

    @PostMapping("/withdrawals")
    fun withdraw(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @RequestBody request: WithdrawalRequest,
    ): ResponseEntity<PostingResponse> =
        respond(idempotencyKey, postingUseCase.withdraw(IdempotencyKey(idempotencyKey), request.accountId, Amount(request.amount)))

    @PostMapping("/transfers")
    fun transfer(
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) idempotencyKey: String,
        @RequestBody request: TransferRequest,
    ): ResponseEntity<PostingResponse> =
        respond(
            idempotencyKey,
            postingUseCase.transfer(IdempotencyKey(idempotencyKey), request.fromAccountId, request.toAccountId, Amount(request.amount)),
        )

    @GetMapping("/{idempotencyKey}")
    fun status(@PathVariable idempotencyKey: String): PostingStatusResponse =
        postingUseCase.find(IdempotencyKey(idempotencyKey))
            ?.let { PostingStatusResponse.completed(idempotencyKey, it) }
            ?: PostingStatusResponse.notProcessed(idempotencyKey)

    private fun respond(idempotencyKey: String, result: PostingResult): ResponseEntity<PostingResponse> {
        val body = PostingResponse.from(result)
        if (result.replayed) return ResponseEntity.ok(body)
        // 키에 / 같은 문자가 있어도 깨지지 않게 인코딩
        val location = UriComponentsBuilder.fromPath("/postings/{key}").buildAndExpand(idempotencyKey).encode().toUri()
        return ResponseEntity.created(location).body(body)
    }

    companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
    }
}
