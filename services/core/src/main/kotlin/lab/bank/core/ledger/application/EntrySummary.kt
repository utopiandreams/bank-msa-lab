package lab.bank.core.ledger.application

import java.time.Instant
import java.util.UUID

// 상태 조회용 거래 요약. 엔티티(JournalEntry)를 모듈 밖으로 내보내지 않는다 (지연 로딩·변경 위험)
// type은 enum이 아니라 문자열: ledger 도메인의 EntryType을 다른 모듈이 import하지 않게
data class EntrySummary(
    val entryId: UUID,
    val type: String,
    val createdAt: Instant,
)
