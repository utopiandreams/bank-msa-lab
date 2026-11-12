package lab.bank.core.ledger.infrastructure

import lab.bank.core.ledger.domain.JournalEntry
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// 애그리거트 루트(JournalEntry)에만 리포지토리를 둔다. JournalLine 리포지토리는 만들지 않는다 (줄은 거래를 통해서만 저장)
interface JournalEntryRepository : JpaRepository<JournalEntry, UUID> {
    // posting이 "이미 처리된 요청인가"를 확인할 때 쓴다. Kotlin 반환 타입을 nullable로 두면 Optional 대신 null을 준다
    fun findByIdempotencyKey(idempotencyKey: String): JournalEntry?
}
