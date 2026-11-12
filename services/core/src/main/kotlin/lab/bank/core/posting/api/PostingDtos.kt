package lab.bank.core.posting.api

import lab.bank.core.posting.application.PostingResult
import lab.bank.core.posting.application.PostingView
import java.time.Instant
import java.util.UUID

// DTO는 원시 타입: API는 외부와의 약속이라 내부 값 객체(Amount 등)가 바뀌어도 흔들리지 않게.
// 값 객체로의 변환(= 검증)은 컨트롤러가 한다. 멱등 키는 body가 아니라 Idempotency-Key 헤더로 받는다 (요청을 식별하는 메타데이터)

data class DepositRequest(
    val accountId: UUID,
    val amount: Long,
)

data class WithdrawalRequest(
    val accountId: UUID,
    val amount: Long,
)

data class TransferRequest(
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: Long,
)

data class PostingResponse(
    val entryId: UUID,
    val replayed: Boolean,
) {
    companion object {
        fun from(result: PostingResult) = PostingResponse(result.entryId, result.replayed)
    }
}

/**
 * 상태 조회 응답. "거래 없음"도 404가 아니라 200 + status로 답한다:
 * - 404는 URL 오타(엔드포인트 없음)와 구분이 안 된다
 * - 상태가 늘어날 수 있다 (8주 차 타행 이체의 "처리 중" 등)
 */
data class PostingStatusResponse(
    val idempotencyKey: String,
    val status: PostingStatus,
    val entryId: UUID? = null,
    val type: String? = null,
    val createdAt: Instant? = null,
) {
    companion object {
        fun completed(idempotencyKey: String, view: PostingView) =
            PostingStatusResponse(idempotencyKey, PostingStatus.COMPLETED, view.entryId, view.type, view.createdAt)

        fun notProcessed(idempotencyKey: String) =
            PostingStatusResponse(idempotencyKey, PostingStatus.NOT_PROCESSED)
    }
}
