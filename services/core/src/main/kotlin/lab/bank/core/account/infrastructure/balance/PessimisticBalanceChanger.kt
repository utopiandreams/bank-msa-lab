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
 * PESSIMISTIC — SELECT ... FOR UPDATE로 행을 잡고 읽는다. 트랜잭션이 끝날 때까지 다른 쪽은 기다린다 → 충돌 자체가 없다.
 *
 * **송금은 두 계좌를 항상 ID 순서로 잠근다.** A→B와 B→A가 동시에 오면, 각자 보내는 계좌부터 잡을 때
 * 서로 상대가 쥔 행을 기다리는 데드락이 생긴다 (PostgreSQL이 1초 뒤 감지해 한쪽을 죽인다). 순서를 하나로 정하면 원이 안 생긴다.
 * 순서 기준은 아무거나 같기만 하면 된다 (자바 UUID 비교는 DB의 uuid 정렬과 다르지만, 모든 파드가 같은 코드라 상관없다)
 */
@Component
class PessimisticBalanceChanger(
    private val repository: AccountRepository,
) : BalanceChanger {

    override val strategy = LockStrategy.PESSIMISTIC

    override fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID) =
        lock(accountId).deposit(amount, ledgerEntryId)

    override fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID, requester: UserId?) {
        val account = lock(accountId)
        requester?.let { AccountOwnership.require(account, it) }
        account.withdraw(amount, ledgerEntryId)
    }

    override fun transfer(requester: UserId, fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        val locked = listOf(fromAccountId, toAccountId).sorted().associateWith { lock(it) }   // ID 순서로 잠금
        AccountOwnership.require(locked.getValue(fromAccountId), requester).withdraw(amount, ledgerEntryId)
        locked.getValue(toAccountId).deposit(amount, ledgerEntryId)
    }

    private fun lock(accountId: UUID): Account =
        repository.findByIdForUpdate(accountId) ?: throw AccountNotFoundException(accountId)
}
