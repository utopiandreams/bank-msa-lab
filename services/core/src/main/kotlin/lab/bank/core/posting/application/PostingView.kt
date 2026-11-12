package lab.bank.core.posting.application

import java.time.Instant
import java.util.UUID

// 처리된 거래 조회 결과. api 계층은 posting만 알고 ledger의 타입(EntrySummary)은 모른다
data class PostingView(
    val entryId: UUID,
    val type: String,
    val createdAt: Instant,
)
