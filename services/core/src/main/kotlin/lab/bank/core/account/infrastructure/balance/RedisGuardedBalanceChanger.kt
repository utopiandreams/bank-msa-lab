package lab.bank.core.account.infrastructure.balance

import lab.bank.core.account.application.UserId
import lab.bank.core.account.application.balance.BalanceChanger
import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.shared.Amount
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * REDIS — 잔액을 바꾸는 코드는 OPTIMISTIC과 같다 (읽고 → 도메인 메서드 → 커밋 때 더티 체킹, @Version 검사).
 * **Redis 락은 여기서 잡지 않는다.** 이 메서드는 posting의 트랜잭션 안에서 불린다. 여기서 잡고 풀면 커밋 **전에** 풀려
 * 다음 요청이 커밋 안 된 옛 잔액을 읽는다 (RedisLockScopeTest). 그래서 락은 posting이 트랜잭션 바깥에서 감싼다.
 *
 * @Version을 남겨 두는 이유: Redis 락이 뚫려도(TTL 만료, GC 멈춤, Redis 재시작) 돈이 깨지지 않게 하는 안전망.
 * 락이 제대로 동작하면 낙관적 락 재시도 지표(core_posting_optimistic_retries_total)는 0이어야 한다 → 그게 락이 동작한다는 증거
 */
@Component
class RedisGuardedBalanceChanger(
    private val optimistic: OptimisticBalanceChanger,
) : BalanceChanger {

    override val strategy = LockStrategy.REDIS

    override fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID) =
        optimistic.deposit(accountId, amount, ledgerEntryId)

    override fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID, requester: UserId?) =
        optimistic.withdraw(accountId, amount, ledgerEntryId, requester)

    override fun transfer(requester: UserId, fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID) =
        optimistic.transfer(requester, fromAccountId, toAccountId, amount, ledgerEntryId)
}
