package lab.bank.core.account.application

import lab.bank.core.account.domain.Account
import lab.bank.core.account.domain.exception.AccountNotFoundException
import lab.bank.core.account.infrastructure.AccountRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import lab.bank.core.shared.Amount
import java.util.UUID

interface AccountUseCase {
    fun create(owner: UserId): Account

    /** 고객 조회: 주인만 볼 수 있다. 남의 계좌는 "없는 계좌"와 똑같이 AccountNotFoundException (존재 여부를 흘리지 않음) */
    fun getOwned(accountId: UUID, requester: UserId): Account

    // posting 전용: 원장 거래 ID와 함께만 잔액을 바꾼다. 호출하는 쪽의 트랜잭션에 합류한다 (전파 REQUIRED)
    fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID)
    fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID)
    // 보내는 계좌는 요청자의 것이어야 한다 (BOLA 방지, G-4). 받는 계좌는 누구 것이든 된다
    fun transfer(requester: UserId, fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID)
}

@Service
class AccountUseCaseImpl(
    private val accountRepository: AccountRepository,   // 생성자 주입 (@Autowired 불필요)
) : AccountUseCase {

    @Transactional
    override fun create(owner: UserId): Account =
        accountRepository.save(Account(ownerId = owner.value))

    @Transactional(readOnly = true)
    override fun getOwned(accountId: UUID, requester: UserId): Account = owned(get(accountId), requester)

    private fun get(accountId: UUID): Account =
        accountRepository.findById(accountId).orElseThrow { AccountNotFoundException(accountId) }

    /**
     * 소유자 검사 (인가). 남의 계좌면 "없음"과 같은 예외를 던진다: 밖에서는 둘을 구분할 수 없어야
     * 아무 ID나 넣어 보며 계좌 존재 여부를 알아내는 것(enumeration)을 막는다. 구분은 로그에만 남긴다 (보안 감사용).
     * (docs/learning/pitfalls.md 1. BOLA, 2. 존재 여부 누출)
     */
    private fun owned(account: Account, requester: UserId): Account {
        if (account.ownerId != requester.value) {
            log.warn("계좌 소유자 불일치: account={}, requester={}", account.id, requester.value)
            throw AccountNotFoundException(account.id)
        }
        return account
    }

    // save()를 부르지 않는다: 트랜잭션 안에서 조회한 엔티티라 커밋 때 더티 체킹으로 UPDATE된다
    @Transactional
    override fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        get(accountId).deposit(amount, ledgerEntryId)
    }

    @Transactional
    override fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        get(accountId).withdraw(amount, ledgerEntryId)
    }

    // 소유자 검사와 출금이 같은 트랜잭션 안: 검사와 사용 사이에 틈이 없다 (TOCTOU 없음, pitfalls 9)
    @Transactional
    override fun transfer(requester: UserId, fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID) {
        owned(get(fromAccountId), requester).withdraw(amount, ledgerEntryId)
        get(toAccountId).deposit(amount, ledgerEntryId)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AccountUseCaseImpl::class.java)
    }
}
