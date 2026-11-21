package lab.bank.core.account.balance

import io.micrometer.core.instrument.MeterRegistry
import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.account.application.balance.LockStrategySwitch
import lab.bank.core.account.domain.exception.InsufficientBalanceException
import lab.bank.core.account.infrastructure.AccountRepository
import lab.bank.core.posting.application.PostingUseCase
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 7주 차: 락 전략별로 동시 출금·송금에서 잔액이 맞는가. 기준은 **원장**: 계좌 잔액 = 그 계좌 원장 줄의 합 (대사).
 * 테스트에 @Transactional을 걸지 않는다: 동시성은 진짜 커밋이 일어나야 재현된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LockStrategyConcurrencyTest {

    @Autowired lateinit var postingUseCase: PostingUseCase
    @Autowired lateinit var accountUseCase: AccountUseCase
    @Autowired lateinit var lockSwitch: LockStrategySwitch
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var transactionTemplate: TransactionTemplate
    @Autowired lateinit var meterRegistry: MeterRegistry
    @MockitoSpyBean lateinit var accountRepository: AccountRepository

    private val owner = UserId("lock-owner")
    private fun newKey() = IdempotencyKey("key-${UUID.randomUUID()}")
    private fun fundedAccount(amount: Long): UUID =
        accountUseCase.create(owner).id.also { postingUseCase.deposit(newKey(), it, Amount(amount)) }
    private fun balanceOf(accountId: UUID): Long = accountUseCase.getOwned(accountId, owner).balance

    // 원장 기준 잔액: 고객예금(부채) 줄의 대변 − 차변
    private fun ledgerBalanceOf(accountId: UUID): Long = jdbcTemplate.queryForObject(
        """
        SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
          FROM ledger.journal_line WHERE account_id = ? AND ledger_account = 'CUSTOMER_DEPOSIT'
        """.trimIndent(),
        Long::class.java, accountId,
    )!!

    private fun retries(): Double = meterRegistry.counter("core.posting.optimistic.retries").count()

    @AfterEach
    fun resetSwitch() {
        lockSwitch.change(LockStrategy.OPTIMISTIC)
    }

    /** 다음 두 번의 계좌 조회(findById)가 서로를 기다리게 한다 → 두 트랜잭션이 **같은 잔액을 읽은 뒤에야** 쓰기로 넘어간다 */
    private fun forceBothReadSameBalance() {
        val armed = AtomicInteger(2)
        val bothRead = CountDownLatch(2)
        // 리포지토리는 인터페이스 프록시라 callRealMethod가 안 된다 → spy의 기본 응답("원래 빈에 위임")으로 진짜 조회 (PostingUseCaseTest와 같은 방식)
        val realCall = mockingDetails(accountRepository).mockCreationSettings.defaultAnswer
        doAnswer { invocation ->
            val result = realCall.answer(invocation)
            if (armed.getAndDecrement() > 0) {   // 경합 두 번만. 재시도·검증용 조회는 그냥 지나간다
                bothRead.countDown()
                bothRead.await(5, TimeUnit.SECONDS)
            }
            result
        }.whenever(accountRepository).findById(any())
    }

    private fun <T> runConcurrently(count: Int, task: (Int) -> T): List<Result<T>> {
        val executor = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        return try {
            val futures = List(count) { i -> executor.submit<T> { start.await(); task(i) } }
            start.countDown()   // 다 맡긴 뒤 한꺼번에 출발
            futures.map { f ->
                try {
                    Result.success(f.get(30, TimeUnit.SECONDS))
                } catch (e: ExecutionException) {
                    Result.failure(e.cause!!)
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    // ───────────── 재현: NONE ─────────────

    @Test
    fun `NONE - 같은 잔액을 읽은 두 출금 중 하나가 사라진다 (lost update), 원장과 잔액이 어긋난다`() {
        val account = fundedAccount(10_000)
        lockSwitch.change(LockStrategy.NONE)
        forceBothReadSameBalance()

        val results = runConcurrently(2) { postingUseCase.withdraw(newKey(), account, Amount(1_000)) }

        assertTrue(results.all { it.isSuccess }, "둘 다 성공으로 응답했다")
        assertEquals(8_000, ledgerBalanceOf(account), "원장에는 출금 2건")
        assertEquals(9_000, balanceOf(account), "잔액에는 1건만: 늦게 쓴 쪽이 먼저 쓴 값을 덮었다")
    }

    // ───────────── OPTIMISTIC: 같은 경합을 감지하고 재시도 ─────────────

    @Test
    fun `OPTIMISTIC - 같은 경합에서 늦은 쪽이 충돌을 감지하고 트랜잭션째 다시 시도해 둘 다 반영된다`() {
        val account = fundedAccount(10_000)
        lockSwitch.change(LockStrategy.OPTIMISTIC)
        val retriesBefore = retries()
        forceBothReadSameBalance()

        val results = runConcurrently(2) { postingUseCase.withdraw(newKey(), account, Amount(1_000)) }

        assertTrue(results.all { it.isSuccess })
        assertEquals(8_000, balanceOf(account))
        assertEquals(8_000, ledgerBalanceOf(account))
        assertTrue(retries() - retriesBefore >= 1, "충돌이 실제로 일어나 재시도했다는 증거")
    }

    // ───────────── 핫 계좌: 2,000원에 100원 출금 40건 동시 ─────────────

    @ParameterizedTest
    @EnumSource(names = ["PESSIMISTIC", "ATOMIC_UPDATE"])
    fun `핫 계좌 - 잔액이 허락하는 만큼만 정확히 성공하고 원장과 잔액이 같다`(strategy: LockStrategy) {
        val account = fundedAccount(2_000)
        lockSwitch.change(strategy)

        val results = runConcurrently(40) { postingUseCase.withdraw(newKey(), account, Amount(100)) }

        assertEquals(20, results.count { it.isSuccess })
        assertEquals(20, results.count { it.exceptionOrNull() is InsufficientBalanceException })
        assertEquals(0, balanceOf(account))
        assertEquals(0, ledgerBalanceOf(account))
    }

    @Test
    fun `핫 계좌 OPTIMISTIC - 돈은 맞지만, 재시도를 다 써서 충돌로 실패하는 요청이 생긴다`() {
        val account = fundedAccount(2_000)
        lockSwitch.change(LockStrategy.OPTIMISTIC)

        val results = runConcurrently(40) { postingUseCase.withdraw(newKey(), account, Amount(100)) }

        val succeeded = results.count { it.isSuccess }
        val conflicted = results.count { it.exceptionOrNull() is OptimisticLockingFailureException }
        val insufficient = results.count { it.exceptionOrNull() is InsufficientBalanceException }
        assertEquals(40, succeeded + conflicted + insufficient, "다른 종류의 실패는 없다: $results")
        assertEquals(2_000 - 100L * succeeded, balanceOf(account))
        assertEquals(balanceOf(account), ledgerBalanceOf(account), "실패한 요청은 원장도 롤백됐다")
        println("핫 계좌 OPTIMISTIC: 성공 $succeeded / 충돌로 실패 $conflicted / 잔액 부족 $insufficient")
    }

    // ───────────── 송금 데드락 ─────────────

    @Test
    fun `재현 - 두 트랜잭션이 두 계좌를 반대 순서로 잠그면 데드락, PostgreSQL이 한쪽을 죽인다`() {
        val a = fundedAccount(1_000)
        val b = fundedAccount(1_000)
        val firstLocked = CountDownLatch(2)

        val results = runConcurrently(2) { i ->
            val (first, second) = if (i == 0) a to b else b to a
            transactionTemplate.execute {
                accountRepository.findByIdForUpdate(first)
                firstLocked.countDown()
                firstLocked.await(5, TimeUnit.SECONDS)   // 둘 다 첫 행을 쥔 뒤에
                accountRepository.findByIdForUpdate(second)   // 상대가 쥔 행을 기다린다 → 원
            }
        }

        assertEquals(1, results.count { it.isSuccess })
        val failure = results.single { it.isFailure }.exceptionOrNull()!!
        assertTrue(failure.stackTraceToString().contains("deadlock"), "데드락으로 죽었다: $failure")
    }

    @ParameterizedTest
    @EnumSource(names = ["PESSIMISTIC", "ATOMIC_UPDATE"])
    fun `A→B와 B→A 송금을 동시에 몰아도 ID 순서로 잠가 데드락 없이 끝나고 합계가 그대로다`(strategy: LockStrategy) {
        val a = fundedAccount(10_000)
        val b = fundedAccount(10_000)
        lockSwitch.change(strategy)

        val results = runConcurrently(40) { i ->
            val (from, to) = if (i % 2 == 0) a to b else b to a
            postingUseCase.transfer(owner, newKey(), from, to, Amount(10))
        }

        assertTrue(results.all { it.isSuccess }, "실패: ${results.filter { it.isFailure }}")
        assertEquals(20_000, balanceOf(a) + balanceOf(b))
        assertEquals(10_000, balanceOf(a))   // 20건씩 같은 금액이 오갔다
        assertEquals(balanceOf(a), ledgerBalanceOf(a))
        assertNotEquals(0, results.size)
    }
}
