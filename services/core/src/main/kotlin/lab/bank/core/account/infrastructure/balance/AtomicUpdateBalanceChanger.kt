package lab.bank.core.account.infrastructure.balance

import lab.bank.core.account.application.UserId
import lab.bank.core.account.application.balance.AccountOwnership
import lab.bank.core.account.application.balance.BalanceChanger
import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.account.domain.exception.AccountNotFoundException
import lab.bank.core.account.domain.exception.InsufficientBalanceException
import lab.bank.core.account.infrastructure.AccountRepository
import lab.bank.core.shared.Amount
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * ATOMIC_UPDATE — 잔액을 앱으로 읽어 오지 않는다. "충분하면 빼라"를 DB에 한 문장으로 시킨다 (AccountRepository.withdrawAtomically 주석).
 *
 * 흐름 (출금):
 *   1. 고객 출금이면 예금주만 조회해 소유자 검사 (예금주는 안 바뀌어서 잠금 없이 읽어도 된다)
 *   2. UPDATE ... SET balance = balance - :amount WHERE id = :id AND balance >= :amount
 *   3. 바뀐 행 수로 판정: 1 = 성공 / 0 = 잔액 부족 (1에서 계좌가 있음을 확인했으니). 내부 출금은 1을 건너뛰고, 0일 때만 없는 계좌인지 확인
 *
 * 대가 (면접 포인트):
 * - **"잔액 ≥ 금액" 규칙이 두 곳에 있다** — Account.withdraw(도메인)와 이 SQL. "상태 변경은 도메인 메서드로만"(CLAUDE.md 7장)의 의도적 예외다.
 *   규칙이 바뀌면(마이너스 통장 한도 등) 둘 다 고쳐야 한다. 그래서 단순한 증감에만 쓰고, 복잡한 규칙엔 락 + 도메인 메서드가 낫다
 * - 실패 이유가 "0행" 하나로 뭉개져서, 이유를 가리려면 조회를 한 번 더 한다
 * - 잠금이 없는 게 아니다: UPDATE도 행 잠금을 커밋까지 쥔다. 차이는 앱 왕복 없이 문장 하나 동안만 계산한다는 것
 *
 * 송금도 두 행을 ID 순서로 UPDATE한다: UPDATE도 행 잠금이라 A→B / B→A가 반대 순서로 잡으면 데드락 (PESSIMISTIC과 같은 이유)
 */
@Component
class AtomicUpdateBalanceChanger(
    private val repository: AccountRepository,
) : BalanceChanger {

    override val strategy = LockStrategy.ATOMIC_UPDATE

    override fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        if (repository.depositAtomically(accountId, amount.value, ledgerEntryId, Instant.now()) == 0) {
            throw AccountNotFoundException(accountId)
        }
    }

    override fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID, requester: UserId?) {
        if (requester != null) checkOwner(accountId, requester)
        if (repository.withdrawAtomically(accountId, amount.value, ledgerEntryId, Instant.now()) == 0) {
            // 고객 출금은 위에서 계좌가 있음을 확인했다. 내부 출금은 여기서 처음 확인한다
            if (requester == null && repository.findOwnerIdById(accountId) == null) throw AccountNotFoundException(accountId)
            throw InsufficientBalanceException(accountId, amount.value)
        }
    }

    override fun transfer(requester: UserId, fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        checkOwner(fromAccountId, requester)
        // ID 순서로 UPDATE (데드락 방지). 받는 쪽을 먼저 늘렸다가 출금이 실패해도, 예외로 트랜잭션 전체가 롤백된다
        if (fromAccountId < toAccountId) {
            withdraw(fromAccountId, amount, ledgerEntryId, requester = null)
            deposit(toAccountId, amount, ledgerEntryId)
        } else {
            deposit(toAccountId, amount, ledgerEntryId)
            withdraw(fromAccountId, amount, ledgerEntryId, requester = null)
        }
    }

    private fun checkOwner(accountId: UUID, requester: UserId) {
        val ownerId = repository.findOwnerIdById(accountId) ?: throw AccountNotFoundException(accountId)
        AccountOwnership.require(accountId, ownerId, requester)
    }
}
