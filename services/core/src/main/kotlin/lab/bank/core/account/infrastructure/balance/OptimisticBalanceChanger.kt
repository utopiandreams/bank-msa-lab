package lab.bank.core.account.infrastructure.balance

import lab.bank.core.account.application.UserId
import lab.bank.core.account.application.balance.AccountOwnership
import lab.bank.core.account.application.balance.BalanceChanger
import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.account.domain.Account
import lab.bank.core.account.domain.exception.AccountNotFoundException
import lab.bank.core.account.infrastructure.AccountRepository
import lab.bank.core.shared.Amount
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * OPTIMISTIC — 잠그지 않고 읽고, 커밋 때 `UPDATE ... WHERE id = ? AND version = ?`로 "그사이 아무도 안 바꿨나"를 확인한다.
 * 0행이면 Hibernate가 ObjectOptimisticLockingFailureException → posting이 트랜잭션째 다시 시도한다 (PostingUseCaseImpl).
 *
 * 충돌이 드물면 가장 싸다 (대기 없음). 핫 계좌처럼 충돌이 잦으면 재시도가 재시도를 부르며 실패가 쏟아진다.
 * save()를 부르지 않는다: 트랜잭션 안에서 읽은 엔티티라 커밋 때 더티 체킹으로 UPDATE된다.
 */
@Component
class OptimisticBalanceChanger(
    private val repository: AccountRepository,
) : BalanceChanger {

    override val strategy = LockStrategy.OPTIMISTIC

    override fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID) =
        load(accountId).deposit(amount, ledgerEntryId)

    override fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID, requester: UserId?) {
        val account = load(accountId)
        requester?.let { AccountOwnership.require(account, it) }
        account.withdraw(amount, ledgerEntryId)
    }

    // 소유자 검사와 출금이 같은 트랜잭션 안: 검사와 사용 사이에 틈이 없다 (TOCTOU 없음, pitfalls 9)
    override fun transfer(requester: UserId, fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        AccountOwnership.require(load(fromAccountId), requester).withdraw(amount, ledgerEntryId)
        load(toAccountId).deposit(amount, ledgerEntryId)
    }

    private fun load(accountId: UUID): Account =
        repository.findById(accountId).orElseThrow { AccountNotFoundException(accountId) }
}
