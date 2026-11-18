package lab.bank.core.ledger.application

import lab.bank.core.ledger.domain.JournalEntry
import lab.bank.core.ledger.domain.LedgerAccount
import lab.bank.core.ledger.domain.exception.IdempotencyConflictException
import lab.bank.core.ledger.domain.exception.OriginalEntryNotFoundException
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

    @Transactional
    override fun recordInterbankWithdraw(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): RecordedEntry =
        record(JournalEntry.interbankWithdrawal(idempotencyKey, accountId, amount))

    @Transactional
    override fun recordInterbankWithdrawCancel(originalKey: IdempotencyKey): RecordedCancel {
        val original = journalEntryRepository.findByIdempotencyKey(originalKey.value)
            ?: throw OriginalEntryNotFoundException(originalKey.value)
        // 되돌릴 고객 계좌와 금액: 원래 전표의 고객 예금 줄 (타행 출금은 고객 예금 줄이 정확히 하나)
        val customerLine = original.lines.single { it.ledgerAccount == LedgerAccount.CUSTOMER_DEPOSIT }
        fun result(entry: JournalEntry, replayed: Boolean) =
            RecordedCancel(entry.id, replayed, entry.idempotencyKey, customerLine.accountId!!, Amount(customerLine.amount))

        // 이미 취소됐으면 그 취소 전표를 돌려준다 (재시도·동시 요청의 두 번째)
        journalEntryRepository.findByReversesEntryId(original.id)?.let { return result(it, replayed = true) }

        val cancel = JournalEntry.cancelOf(original)   // 타행 출금이 아니면 여기서 NotCancellableEntryException
        // 동시에 두 번 오면 늦은 쪽이 reverses_entry_id UNIQUE 위반 → posting이 새 트랜잭션으로 재시도 → 위 조회에서 replayed
        journalEntryRepository.saveAndFlush(cancel)
        return result(cancel, replayed = false)
    }

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
