package lab.bank.core.ledger.application

import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import java.time.Instant
import java.util.UUID

// ledger 모듈의 공개 인터페이스. posting은 리포지토리나 엔티티가 아니라 이것만 쓴다
interface LedgerUseCase {
    /**
     * 입금 거래를 멱등하게 기록한다. 호출하는 쪽의 트랜잭션에 합류한다.
     * - 같은 키 거래가 없으면 기록 → replayed = false
     * - 같은 키 + 같은 내용이면 기존 거래 → replayed = true (호출자는 잔액을 다시 바꾸면 안 된다)
     * - 같은 키 + 다른 내용이면 IdempotencyConflictException
     */
    fun recordDeposit(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): RecordedEntry
    fun recordWithdraw(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): RecordedEntry
    fun recordTransfer(idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): RecordedEntry
    fun recordInterbankWithdraw(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): RecordedEntry

    /**
     * 타행 출금 취소(보상)를 멱등하게 기록한다. 금액·계좌는 원래 전표에서 가져온다.
     * - 원래 전표가 없으면 OriginalEntryNotFoundException, 타행 출금이 아니면 NotCancellableEntryException
     * - 이미 취소됐으면 기존 취소 전표 → replayed = true (한 전표는 한 번만 취소된다: reverses_entry_id UNIQUE)
     */
    fun recordInterbankWithdrawCancel(originalKey: IdempotencyKey): RecordedCancel

    /**
     * 취소하면 잔액이 돌아갈 계좌 (원래 전표의 고객 예금 계좌). 원래 전표가 없거나 고객 계좌가 하나가 아니면 null.
     * posting이 트랜잭션 **전에** 계좌 잠금 키를 알아내려고 쓴다 (REDIS 전략). 전표는 불변이라 트랜잭션 밖에서 읽어도 안전하다
     */
    fun findCancelTargetAccountId(originalKey: IdempotencyKey): UUID?

    // 상태 조회: 그 키로 커밋된 거래가 있으면 요약, 없으면 null
    fun findEntry(idempotencyKey: IdempotencyKey): EntrySummary?

    /**
     * 대사 추출 (ADR-0006): [from, to)에 기록된 전표 중 types에 해당하는 것을 한 줄씩 넘긴다 (DB 커서, 100만 건 대비).
     * 전표는 불변이라 만든 날 하나로 충분하다
     */
    fun forEachEntryCreatedBetween(from: Instant, to: Instant, types: Set<String>, action: (EntryExtractRow) -> Unit)

    /** 시산표 (ADR-0006 결정 1): [from, to)에 기록된 모든 전표 줄의 차변 합·대변 합 */
    fun totalsBetween(from: Instant, to: Instant): DailyTotals
}
