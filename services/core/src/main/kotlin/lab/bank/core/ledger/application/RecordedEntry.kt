package lab.bank.core.ledger.application

import java.util.UUID

// 기록 결과. 엔티티(JournalEntry)를 모듈 밖으로 내보내지 않으려고 필요한 값만 담는다
data class RecordedEntry(
    val entryId: UUID,
    val replayed: Boolean,   // true: 이미 처리된 요청 (이번 호출에서는 아무것도 기록하지 않음)
)
