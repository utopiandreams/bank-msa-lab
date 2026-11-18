package lab.bank.core.posting.application

import java.util.UUID

// 취소 처리 결과. 취소 전표의 키는 core가 만들므로("{원래 키}-cancel") 호출자에게 알려 준다 (상태 조회용)
data class CancelResult(
    val entryId: UUID,
    val replayed: Boolean,
    val idempotencyKey: String,
)
