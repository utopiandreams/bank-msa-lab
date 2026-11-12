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

    /**
     * 상태 조회: 이 키로 처리(커밋)된 거래가 있나. 없으면 null.
     * 주의: null은 "실패"가 아니라 "아직 커밋된 기록이 없다"는 뜻이다. 조회 순간 처리 중일 수도 있다.
     * → 호출자는 실패로 확정하지 말고 같은 키로 다시 보낸다 (멱등이라 안전)
     */
    fun find(idempotencyKey: IdempotencyKey): PostingView?
}
