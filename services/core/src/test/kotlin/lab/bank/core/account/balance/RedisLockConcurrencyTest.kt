package lab.bank.core.account.balance

import io.micrometer.core.instrument.MeterRegistry
import lab.bank.core.RedisTestcontainerConfiguration
import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.account.application.balance.LockStrategySwitch
import lab.bank.core.account.domain.exception.InsufficientBalanceException
import lab.bank.core.posting.application.PostingUseCase
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REDIS·REDIS_SPIN을 posting 전체 경로로: 잠금이 트랜잭션을 감싸면 버전 충돌이 **한 번도** 안 나야 한다.
 * 재시도 지표가 0 = 다음 요청이 항상 커밋된 값을 읽었다 = 잠금 범위가 맞다 (pitfalls 34).
 * 대기 상한은 넉넉히(10초): 여기서 보려는 건 정합성. 짧은 상한에서 503이 얼마나 나는지는 k6에서 본다
 */
@SpringBootTest(properties = ["core.lock.redis.wait-time=10s"])
@Import(TestcontainersConfiguration::class, RedisTestcontainerConfiguration::class)
class RedisLockConcurrencyTest {

    @Autowired lateinit var postingUseCase: PostingUseCase
    @Autowired lateinit var accountUseCase: AccountUseCase
    @Autowired lateinit var lockSwitch: LockStrategySwitch
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var meterRegistry: MeterRegistry

    private val owner = UserId("redis-concurrency-owner")
    private fun newKey() = IdempotencyKey("key-${UUID.randomUUID()}")
    private fun fundedAccount(amount: Long): UUID =
        accountUseCase.create(owner).id.also { postingUseCase.deposit(newKey(), it, Amount(amount)) }
    private fun balanceOf(accountId: UUID): Long = accountUseCase.getOwned(accountId, owner).balance
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
        lockSwitch.reset()
    }

    private fun <T> runConcurrently(count: Int, task: (Int) -> T): List<Result<T>> {
        val executor = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        return try {
            val futures = List(count) { i -> executor.submit<T> { start.await(); task(i) } }
            start.countDown()
            futures.map { f ->
                try {
                    Result.success(f.get(60, TimeUnit.SECONDS))
                } catch (e: ExecutionException) {
                    Result.failure(e.cause!!)
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    @ParameterizedTest
    @EnumSource(value = LockStrategy::class, names = ["REDIS", "REDIS_SPIN"])
    fun `핫 계좌 - 2,000원에 100원 출금 40건 동시, 정확히 20건 성공하고 버전 충돌은 0`(strategy: LockStrategy) {
        val account = fundedAccount(2_000)
        lockSwitch.change(strategy)
        val retriesBefore = retries()

        val results = runConcurrently(40) { postingUseCase.withdraw(newKey(), account, Amount(100)) }

        assertEquals(20, results.count { it.isSuccess })
        assertTrue(results.filter { it.isFailure }.all { it.exceptionOrNull() is InsufficientBalanceException },
            "실패는 전부 잔액 부족이어야 한다: ${results.mapNotNull { it.exceptionOrNull()?.javaClass?.simpleName }.toSet()}")
        assertEquals(0, balanceOf(account))
        assertEquals(0, ledgerBalanceOf(account))
        assertEquals(0.0, retries() - retriesBefore, "재시도가 났다 = 누군가 커밋 전 값을 읽었다 = 잠금이 트랜잭션을 못 감쌌다")
    }

    @ParameterizedTest
    @EnumSource(value = LockStrategy::class, names = ["REDIS", "REDIS_SPIN"])
    fun `A→B, B→A 송금 40건 동시 - 두 계좌를 ID 순서로 잡아 데드락 없이 전부 성공, 합계 그대로`(strategy: LockStrategy) {
        val a = fundedAccount(10_000)
        val b = fundedAccount(10_000)
        lockSwitch.change(strategy)
        val retriesBefore = retries()

        val results = runConcurrently(40) { i ->
            if (i % 2 == 0) postingUseCase.transfer(owner, newKey(), a, b, Amount(100))
            else postingUseCase.transfer(owner, newKey(), b, a, Amount(100))
        }

        assertTrue(results.all { it.isSuccess }, "실패: ${results.mapNotNull { it.exceptionOrNull()?.javaClass?.simpleName }}")
        assertEquals(10_000, balanceOf(a))   // 20건씩 주고받음
        assertEquals(10_000, balanceOf(b))
        assertEquals(0.0, retries() - retriesBefore)
    }

    @ParameterizedTest
    @EnumSource(value = LockStrategy::class, names = ["REDIS", "REDIS_SPIN"])
    fun `타행 출금 취소 - 계좌를 몰라도 원장에서 찾아 잠그고, 동시 입금과 섞여도 충돌 0`(strategy: LockStrategy) {
        val account = fundedAccount(10_000)
        lockSwitch.change(strategy)
        val withdrawKey = newKey()
        postingUseCase.interbankWithdraw(owner, withdrawKey, account, Amount(3_000))
        val retriesBefore = retries()

        // 취소 1건 + 같은 계좌 입금 19건을 동시에
        val results = runConcurrently(20) { i ->
            if (i == 0) postingUseCase.cancelInterbankWithdraw(withdrawKey)
            else postingUseCase.deposit(newKey(), account, Amount(100))
        }

        assertTrue(results.all { it.isSuccess })
        assertEquals(10_000 + 19 * 100, balanceOf(account))
        assertEquals(0.0, retries() - retriesBefore, "취소가 계좌를 못 잠갔다면 입금과 버전 충돌이 났을 것")
    }
}
