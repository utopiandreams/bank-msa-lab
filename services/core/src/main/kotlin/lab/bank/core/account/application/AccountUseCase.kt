package lab.bank.core.account.application

import lab.bank.core.account.domain.Account
import lab.bank.core.account.domain.exception.AccountNotFoundException
import lab.bank.core.account.infrastructure.AccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import lab.bank.core.shared.Amount
import java.util.UUID

interface AccountUseCase {
    fun create(userId: UserId): Account
    fun get(accountId: UUID): Account

    // posting 전용: 원장 거래 ID와 함께만 잔액을 바꾼다. 호출하는 쪽의 트랜잭션에 합류한다 (전파 REQUIRED)
    fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID)
    fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID)
    fun transfer(fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID)
}

@Service
class AccountUseCaseImpl(
    private val accountRepository: AccountRepository,   // 생성자 주입 (@Autowired 불필요)
) : AccountUseCase {

    @Transactional
    override fun create(userId: UserId): Account =
        accountRepository.save(Account(ownerId = userId.userId))

    @Transactional(readOnly = true)
    override fun get(accountId: UUID): Account =
        accountRepository.findById(accountId).orElseThrow { AccountNotFoundException(accountId) }

    // save()를 부르지 않는다: 트랜잭션 안에서 조회한 엔티티라 커밋 때 더티 체킹으로 UPDATE된다
    @Transactional
    override fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        get(accountId).deposit(amount, ledgerEntryId)
    }

    @Transactional
    override fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        get(accountId).withdraw(amount, ledgerEntryId)
    }

    @Transactional
    override fun transfer(fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        get(fromAccountId).withdraw(amount, ledgerEntryId)
        get(toAccountId).deposit(amount, ledgerEntryId)
    }
}

data class UserId(
    val userId: String
)
