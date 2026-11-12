package lab.bank.core.ledger.application

import java.util.UUID

// ledger 모듈의 공개 인터페이스. posting은 리포지토리나 엔티티가 아니라 이것만 쓴다
interface LedgerUseCase {
    /**
     * 입금 거래를 멱등하게 기록한다. 호출하는 쪽의 트랜잭션에 합류한다.
     * - 같은 키 거래가 없으면 기록 → replayed = false
     * - 같은 키 + 같은 내용이면 기존 거래 → replayed = true (호출자는 잔액을 다시 바꾸면 안 된다)
     * - 같은 키 + 다른 내용이면 IdempotencyConflictException
     */
    fun recordDeposit(idempotencyKey: String, accountId: UUID, amount: Long): RecordedEntry
    fun recordWithdraw(idempotencyKey: String, accountId: UUID, amount: Long): RecordedEntry
    fun recordTransfer(idempotencyKey: String, fromAccountId: UUID, toAccountId: UUID, amount: Long): RecordedEntry
}
