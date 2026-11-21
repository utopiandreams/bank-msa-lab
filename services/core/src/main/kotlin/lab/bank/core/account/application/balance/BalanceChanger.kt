package lab.bank.core.account.application.balance

import lab.bank.core.account.application.UserId
import lab.bank.core.shared.Amount
import java.util.UUID

/**
 * 잔액 변경을 락 전략별로 구현한다 (전략 하나 = 구현 하나, account/infrastructure/balance/).
 * 호출하는 쪽(AccountUseCaseImpl)의 트랜잭션 안에서 불린다. 소유자 검사도 여기서: 잠금을 잡는 조회와 같은 조회로 해야 해서
 *
 * 실패는 전략과 상관없이 같은 예외: 없는 계좌·남의 계좌 → AccountNotFoundException, 잔액 부족 → InsufficientBalanceException
 */
interface BalanceChanger {
    val strategy: LockStrategy

    fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID)

    /** requester가 있으면 그 사람 계좌여야 한다 (고객 출금). null이면 내부 업무 출금 */
    fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID, requester: UserId?)

    /** 보내는 계좌는 requester 것이어야 한다. 받는 계좌는 누구 것이든 */
    fun transfer(requester: UserId, fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID)
}
