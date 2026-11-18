package lab.bank.core.posting.application

import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import lab.bank.core.account.domain.exception.AccountNotFoundException
import lab.bank.core.account.domain.exception.InsufficientBalanceException
import lab.bank.core.ledger.domain.Direction
import lab.bank.core.ledger.domain.LedgerAccount
import lab.bank.core.ledger.domain.exception.NotCancellableEntryException
import lab.bank.core.ledger.domain.exception.OriginalEntryNotFoundException
import lab.bank.core.ledger.infrastructure.JournalEntryRepository
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 타행 이체 출금·취소 (ADR-0004, S-2). PostingUseCaseTest와 같은 방식: 진짜 Postgres, 테스트마다 새 계좌·키
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class InterbankPostingTest {

    @Autowired lateinit var postingUseCase: PostingUseCase
    @Autowired lateinit var accountUseCase: AccountUseCase
    @Autowired lateinit var journalEntryRepository: JournalEntryRepository
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    private val owner = UserId("u-1")
    private fun newKey() = IdempotencyKey("tr-${UUID.randomUUID()}")
    private fun balanceOf(accountId: UUID) = accountUseCase.getOwned(accountId, owner).balance
    private fun fundedAccount(amount: Long): UUID =
        accountUseCase.create(owner).id.also { postingUseCase.deposit(newKey(), it, Amount(amount)) }

    // 원장 줄: (계정, 방향, 금액). 지연 로딩이라 트랜잭션 안에서 읽는다
    private fun linesOf(key: String) = transactionTemplate.execute {
        journalEntryRepository.findByIdempotencyKey(key)!!.lines.map { Triple(it.ledgerAccount, it.direction, it.amount) }.toSet()
    }!!

    // 이 테스트의 키들에 대해서만 본 미정산 계정 합 (대변 +, 차변 -). 출금 후 취소까지 끝나면 0이어야 한다
    private fun unsettledOf(vararg keys: String): Long = transactionTemplate.execute {
        keys.mapNotNull { journalEntryRepository.findByIdempotencyKey(it) }
            .flatMap { it.lines }
            .filter { it.ledgerAccount == LedgerAccount.INTERBANK_UNSETTLED }
            .sumOf { if (it.direction == Direction.CREDIT) it.amount else -it.amount }
    }!!

    @Test
    fun `타행 출금하면 잔액이 줄고 원장은 고객 예금 차변 - 미정산 대변`() {
        val acc = fundedAccount(100_000)
        val key = newKey()

        postingUseCase.interbankWithdraw(owner, key, acc, Amount(30_000))

        assertEquals(70_000, balanceOf(acc))
        assertEquals(
            setOf(
                Triple(LedgerAccount.CUSTOMER_DEPOSIT, Direction.DEBIT, 30_000L),
                Triple(LedgerAccount.INTERBANK_UNSETTLED, Direction.CREDIT, 30_000L),
            ),
            linesOf(key.value),
        )
        assertEquals(30_000, unsettledOf(key.value))
    }

    @Test
    fun `남의 계좌에서 타행 출금하면 없는 계좌와 같은 예외 - 잔액·원장 그대로`() {
        val acc = fundedAccount(100_000)
        val key = newKey()

        assertFailsWith<AccountNotFoundException> {
            postingUseCase.interbankWithdraw(UserId("intruder"), key, acc, Amount(30_000))
        }
        assertEquals(100_000, balanceOf(acc))
        assertNull(journalEntryRepository.findByIdempotencyKey(key.value))
    }

    @Test
    fun `잔액 부족이면 예외 - 원장도 롤백`() {
        val acc = fundedAccount(10_000)
        val key = newKey()

        assertFailsWith<InsufficientBalanceException> { postingUseCase.interbankWithdraw(owner, key, acc, Amount(30_000)) }
        assertEquals(10_000, balanceOf(acc))
        assertNull(journalEntryRepository.findByIdempotencyKey(key.value))
    }

    @Test
    fun `취소하면 잔액이 돌아오고 원장에 반대 전표가 생기며 미정산 합은 0`() {
        val acc = fundedAccount(100_000)
        val key = newKey()
        val withdrawal = postingUseCase.interbankWithdraw(owner, key, acc, Amount(30_000))

        val cancel = postingUseCase.cancelInterbankWithdraw(key)

        assertFalse(cancel.replayed)
        assertEquals("${key.value}-cancel", cancel.idempotencyKey)
        assertEquals(100_000, balanceOf(acc))
        assertEquals(
            setOf(
                Triple(LedgerAccount.CUSTOMER_DEPOSIT, Direction.CREDIT, 30_000L),
                Triple(LedgerAccount.INTERBANK_UNSETTLED, Direction.DEBIT, 30_000L),
            ),
            linesOf(cancel.idempotencyKey),
        )
        assertEquals(withdrawal.entryId, journalEntryRepository.findByIdempotencyKey(cancel.idempotencyKey)!!.reversesEntryId)
        // 원장은 불변: 원래 출금 전표는 그대로 남아 있다 (두 줄 → 네 줄)
        assertEquals(2, linesOf(key.value).size)
        assertEquals(0, unsettledOf(key.value, cancel.idempotencyKey))
    }

    @Test
    fun `같은 출금을 두 번 취소하면 한 번만 돌려주고 같은 취소 전표를 받는다`() {
        val acc = fundedAccount(100_000)
        val key = newKey()
        postingUseCase.interbankWithdraw(owner, key, acc, Amount(30_000))

        val first = postingUseCase.cancelInterbankWithdraw(key)
        val again = postingUseCase.cancelInterbankWithdraw(key)

        assertTrue(again.replayed)
        assertEquals(first.entryId, again.entryId)
        assertEquals(100_000, balanceOf(acc))
    }

    @Test
    fun `같은 출금 취소가 동시에 와도 한 번만 돌려준다 - reverses_entry_id UNIQUE`() {
        val acc = fundedAccount(100_000)
        val key = newKey()
        postingUseCase.interbankWithdraw(owner, key, acc, Amount(30_000))

        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val futures = (1..threads).map {
            pool.submit<CancelResult> { start.await(); postingUseCase.cancelInterbankWithdraw(key) }
        }
        start.countDown()
        val results = futures.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, results.map { it.entryId }.toSet().size, "취소 전표가 여러 개 생겼다")
        assertEquals(1, results.count { !it.replayed })
        assertEquals(100_000, balanceOf(acc), "두 번 이상 돌려줬다")
    }

    @Test
    fun `원래 출금이 없으면 취소하지 않는다 - 돈이 허공에서 생기지 않게`() {
        assertFailsWith<OriginalEntryNotFoundException> { postingUseCase.cancelInterbankWithdraw(newKey()) }
    }

    @Test
    fun `타행 출금이 아닌 거래는 이 API로 취소할 수 없다`() {
        val acc = accountUseCase.create(owner).id
        val depositKey = newKey()
        postingUseCase.deposit(depositKey, acc, Amount(50_000))

        assertFailsWith<NotCancellableEntryException> { postingUseCase.cancelInterbankWithdraw(depositKey) }
        assertEquals(50_000, balanceOf(acc))
    }
}
