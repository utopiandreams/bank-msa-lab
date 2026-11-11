package lab.bank.core.account.application

import lab.bank.core.account.domain.Account
import lab.bank.core.account.domain.exception.AccountNotFoundException
import lab.bank.core.account.infrastructure.AccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface AccountUseCase {
    fun create(userId: UserId): Account
    fun get(accountId: UUID): Account
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
}

data class UserId(
    val userId: String
)
