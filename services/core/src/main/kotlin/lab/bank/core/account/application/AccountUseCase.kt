package lab.bank.core.account.application

import lab.bank.core.account.application.balance.AccountOwnership
import lab.bank.core.account.application.balance.BalanceChanger
import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.account.application.balance.LockStrategySwitch
import lab.bank.core.account.domain.Account
import lab.bank.core.account.domain.exception.AccountNotFoundException
import lab.bank.core.account.infrastructure.AccountRepository
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
    // 고객이 시킨 출금(타행 이체): 요청자의 계좌여야 한다 (BOLA 방지). 내부 업무 출금(withdraw)과 구분
    fun withdrawOwned(requester: UserId, accountId: UUID, amount: Amount, ledgerEntryId: UUID)
    // 보내는 계좌는 요청자의 것이어야 한다 (BOLA 방지, G-4). 받는 계좌는 누구 것이든 된다
    fun transfer(requester: UserId, fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID)
}

/**
 * 잔액 변경은 지금 켜진 락 전략의 BalanceChanger에 맡긴다 (7주 차). 여기는 트랜잭션 경계(합류)와 전략 고르기만.
 * 전략은 메서드가 불릴 때 한 번 읽는다 → 요청 하나는 처음부터 끝까지 한 전략으로 처리된다
 */
@Service
class AccountUseCaseImpl(
    private val accountRepository: AccountRepository,   // 생성자 주입 (@Autowired 불필요)
    private val lockSwitch: LockStrategySwitch,
    changers: List<BalanceChanger>,                       // Spring이 BalanceChanger 구현 빈을 전부 모아 준다
) : AccountUseCase {

    private val changers: Map<LockStrategy, BalanceChanger> = changers.associateBy { it.strategy }

    init {
        // 전략을 추가하고 구현을 빠뜨리면 앱이 뜨지 않게 한다 (실험 중에 스위치를 바꿨다가 그때 터지는 것보다 낫다)
        val missing = LockStrategy.entries - this.changers.keys
        check(missing.isEmpty()) { "구현이 없는 락 전략: $missing" }
    }

    private fun changer(): BalanceChanger = changers.getValue(lockSwitch.strategy)

    @Transactional
    override fun create(owner: UserId): Account =
        accountRepository.save(Account(ownerId = owner.value))

    @Transactional(readOnly = true)
    override fun getOwned(accountId: UUID, requester: UserId): Account =
        AccountOwnership.require(accountRepository.findById(accountId).orElseThrow { AccountNotFoundException(accountId) }, requester)

    @Transactional
    override fun deposit(accountId: UUID, amount: Amount, ledgerEntryId: UUID) =
        changer().deposit(accountId, amount, ledgerEntryId)

    @Transactional
    override fun withdraw(accountId: UUID, amount: Amount, ledgerEntryId: UUID) =
        changer().withdraw(accountId, amount, ledgerEntryId, requester = null)

    @Transactional
    override fun withdrawOwned(requester: UserId, accountId: UUID, amount: Amount, ledgerEntryId: UUID) =
        changer().withdraw(accountId, amount, ledgerEntryId, requester)

    // 소유자 검사와 출금이 같은 트랜잭션 안: 검사와 사용 사이에 틈이 없다 (TOCTOU 없음, pitfalls 9)
    @Transactional
    override fun transfer(requester: UserId, fromAccountId: UUID, toAccountId: UUID, amount: Amount, ledgerEntryId: UUID) =
        changer().transfer(requester, fromAccountId, toAccountId, amount, ledgerEntryId)
}
