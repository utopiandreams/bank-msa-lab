package lab.bank.transfer.infrastructure.core

import java.util.UUID

// core API와 주고받는 형식. core 클래스를 import하지 않고 transfer 쪽에 따로 둔다:
// 서비스끼리 코드를 공유하면 한쪽 배포가 다른 쪽을 묶는다. 계약은 HTTP JSON 형식 그 자체다.
// (DDD 용어로는 ACL, Anti-Corruption Layer: core의 모델이 transfer 도메인으로 새어 들어오지 않게 어댑터에서 번역)
//
// 원본: services/core/.../posting/api/PostingDtos.kt, PostingExceptionHandler.kt

// POST /postings/transfers 요청 body (멱등 키는 body가 아니라 Idempotency-Key 헤더)
data class CoreTransferRequest(
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amount: Long,
)

// 2xx 응답 body. 처음 처리 201, 같은 키 재요청 200 + replayed=true
data class CorePostingResponse(
    val entryId: UUID,
    val replayed: Boolean,
)

// 4xx 응답 body: RFC 9457 Problem Details + core가 붙인 code 속성 (예: INSUFFICIENT_BALANCE)
// 필요한 필드만 받는다. 모르는 필드는 무시 (Jackson 3 기본값)
data class CoreProblem(
    val status: Int?,
    val title: String?,
    val detail: String?,
    val code: String?,
)
