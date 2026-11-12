package lab.bank.core.posting.application

import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import java.util.UUID

// posting 모듈: 잔액 변경(account)과 원장 기록(ledger)을 한 트랜잭션으로 지휘한다 (모듈 간 오케스트레이션 계층)
// 의존 방향: posting → account, posting → ledger. account와 ledger는 서로를 모른다
interface PostingUseCase {
    fun deposit(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult
    fun withdraw(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult
    fun transfer(idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): PostingResult
}
