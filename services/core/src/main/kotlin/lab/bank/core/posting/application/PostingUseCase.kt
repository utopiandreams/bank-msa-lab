package lab.bank.core.posting.application

import lab.bank.core.account.application.UserId
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import java.util.UUID

// posting 모듈: 잔액 변경(account)과 원장 기록(ledger)을 한 트랜잭션으로 지휘한다 (모듈 간 오케스트레이션 계층)
// 의존 방향: posting → account, posting → ledger. account와 ledger는 서로를 모른다
interface PostingUseCase {
    fun deposit(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult
    fun withdraw(idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult
    // requester: 보내는 계좌의 주인이어야 한다 (G-4). 입금·출금은 내부 업무(창구·ATM 흉내)라 소유자 검사가 없다
    fun transfer(requester: UserId, idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): PostingResult

    // 타행 이체 출금 (ADR-0004): 고객 예금 → 미정산. 요청자의 계좌여야 한다. 키 = transfer의 송금 ID
    fun interbankWithdraw(requester: UserId, idempotencyKey: IdempotencyKey, accountId: UUID, amount: Amount): PostingResult

    /**
     * 타행 이체 출금 취소 (사가의 보상). 원래 출금의 키로 가리키면 그 전표를 정확히 되돌린다.
     * 소유자 검사 없음: 고객 요청이 아니라 transfer가 사가를 마무리하는 내부 동작이고, 금액·계좌를 요청에서 받지 않는다.
     * 같은 출금을 여러 번 취소해도 한 번만 반영된다 (replayed).
     */
    fun cancelInterbankWithdraw(originalKey: IdempotencyKey): CancelResult

    /**
     * 상태 조회: 이 키로 처리(커밋)된 거래가 있나. 없으면 null.
     * 주의: null은 "실패"가 아니라 "아직 커밋된 기록이 없다"는 뜻이다. 조회 순간 처리 중일 수도 있다.
     * → 호출자는 실패로 확정하지 말고 같은 키로 다시 보낸다 (멱등이라 안전)
     */
    fun find(idempotencyKey: IdempotencyKey): PostingView?
}
