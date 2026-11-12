package lab.bank.core.ledger.application

import lab.bank.core.ledger.domain.JournalEntry
import lab.bank.core.ledger.domain.exception.IdempotencyConflictException
import lab.bank.core.ledger.infrastructure.JournalEntryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import java.util.UUID

@Service
class LedgerUseCaseImpl(
    private val journalEntryRepository: JournalEntryRepository,
) : LedgerUseCase {

    @Transactional
    override fun recordDeposit(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): RecordedEntry =
        record(JournalEntry.deposit(idempotencyKey, accountId, amount))

    @Transactional
    override fun recordWithdraw(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): RecordedEntry =
        record(JournalEntry.withdrawal(idempotencyKey, accountId, amount))

    @Transactional
    override fun recordTransfer(idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): RecordedEntry =
        record(JournalEntry.transfer(idempotencyKey, fromAccountId, toAccountId, amount))

    @Transactional(readOnly = true)
    override fun findEntry(idempotencyKey: IdempotencyKey): EntrySummary? =
        journalEntryRepository.findByIdempotencyKey(idempotencyKey.value)
            ?.let { EntrySummary(it.id, it.type.name, it.createdAt) }

    // 출금·송금도 같은 흐름이라 공통으로 둔다 (팩토리만 다르다)
    private fun record(candidate: JournalEntry): RecordedEntry {
        journalEntryRepository.findByIdempotencyKey(candidate.idempotencyKey)
            // 이미 멱등키로 조회했을 때 결과가 있는 경우
            ?.let { existing ->
                if (!existing.isSameRequestAs(candidate)) throw IdempotencyConflictException(candidate.idempotencyKey)
                return RecordedEntry(existing.id, replayed = true)
            }

        // saveAndFlush: INSERT를 지금 보낸다. 동시 중복 요청이면 UNIQUE 위반이 커밋 시점이 아니라 여기서 터져서
        // Spring의 DataIntegrityViolationException으로 확실히 번역된다 (posting이 그 타입으로 잡는다)
        journalEntryRepository.saveAndFlush(candidate) // 여기서 duplicate exception 발생 가능
        return RecordedEntry(candidate.id, replayed = false)
    }
}
