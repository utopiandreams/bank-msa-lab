package lab.bank.core.account.infrastructure.balance

import jakarta.persistence.EntityManager
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
 * NONE — **재현용.** 읽기(잠금 없음) → 도메인 메서드로 계산 → 버전 검사 없이 덮어쓰기.
 *
 * 두 트랜잭션이 같은 잔액 10,000을 읽고 각자 1,000을 빼서 9,000을 쓰면, 결과는 8,000이 아니라 9,000 (lost update).
 * 원장에는 출금이 2건 남으므로 "원장 합 ≠ 잔액"이 된다 = 대사가 깨진다.
 *
 * Account에 @Version이 있어서, 엔티티를 그대로 두면 더티 체킹이 버전 검사 UPDATE를 날려 충돌을 잡아 버린다
 * → 계산 뒤 엔티티를 영속성 컨텍스트에서 떼어 내고(detach) 벌크 UPDATE로 직접 쓴다.
 */
@Component
class NoLockBalanceChanger(
    private val repository: AccountRepository,
    private val entityManager: EntityManager,
) : BalanceChanger {

    override val strategy = LockStrategy.NONE

    override fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID) =
        overwrite(load(accountId).apply { deposit(amount, ledgerEntryId) })

    override fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID, requester: UserId?) {
        val account = load(accountId)
        requester?.let { AccountOwnership.require(account, it) }
        overwrite(account.apply { withdraw(amount, ledgerEntryId) })   // 잔액 부족 검사는 도메인 그대로 (단, 옛 잔액으로)
    }

    override fun transfer(requester: UserId, fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        val from = AccountOwnership.require(load(fromAccountId), requester)
        val to = load(toAccountId)
        overwrite(from.apply { withdraw(amount, ledgerEntryId) })
        overwrite(to.apply { deposit(amount, ledgerEntryId) })
    }

    private fun load(accountId: UUID): Account =
        repository.findById(accountId).orElseThrow { AccountNotFoundException(accountId) }

    private fun overwrite(account: Account) {
        entityManager.detach(account)   // 벌크 UPDATE 전의 자동 flush가 이 엔티티를 버전 검사로 쓰지 않게 먼저 뗀다
        repository.overwriteBalance(account.id, account.balance, account.lastLedgerId!!, account.updatedAt)
    }
}
