package lab.bank.core.account.balance

import lab.bank.core.RedisTestcontainerConfiguration
import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.account.application.AccountUseCase
import lab.bank.core.account.application.UserId
import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.account.application.balance.LockStrategySwitch
import lab.bank.core.posting.application.PostingUseCase
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 7주 차 REDIS 전략 전에 재현하는 함정: **Redis 락을 어디서 풀어야 하나.**
 *
 * DB 락(행 잠금)은 커밋할 때 DB가 푼다. Redis 락은 코드가 unlock()을 부르는 순간 풀린다.
 * 트랜잭션 **안에서** 풀면 "락은 풀렸는데 내 변경은 아직 커밋 전"인 틈이 생기고, 다음 요청이 그 틈에 옛 잔액을 읽는다.
 *
 * 여기서는 posting의 래퍼(개발자 구현 예정) 대신 테스트가 직접 RedissonClient로 락을 잡는다.
 * 잔액 변경은 진짜 AccountUseCase를 탄다 (REDIS 전략 = @Version 안전망 있음, NONE = 안전망 없음).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, RedisTestcontainerConfiguration::class)
class RedisLockScopeTest {

    @Autowired lateinit var redisson: RedissonClient
    @Autowired lateinit var accountUseCase: AccountUseCase
    @Autowired lateinit var postingUseCase: PostingUseCase
    @Autowired lateinit var lockSwitch: LockStrategySwitch
    @Autowired lateinit var transactionTemplate: TransactionTemplate
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val owner = UserId("redis-lock-owner")
    private fun fundedAccount(amount: Long): UUID = accountUseCase.create(owner).id
        .also { postingUseCase.deposit(IdempotencyKey("key-${UUID.randomUUID()}"), it, Amount(amount)) }
    private fun balanceOf(accountId: UUID): Long = accountUseCase.getOwned(accountId, owner).balance

    // 원장 없이 잔액만 본다 (last_ledger_id는 FK가 아님). 이 테스트의 관심사는 락 범위뿐
    private fun withdraw(accountId: UUID, amount: Long) = accountUseCase.withdraw(accountId, Amount(amount), UUID.randomUUID())

    // RLock은 잡은 스레드만 풀 수 있다 → 같은 스레드 안에서 잡고 푼다. lock(): 리스 시간 없음 = 워치독이 TTL을 계속 늘림
    private fun <T> withRedisLock(accountId: UUID, block: () -> T): T {
        val lock = redisson.getLock("test-lock:account:$accountId")
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    // 다른 트랜잭션이 행 잠금을 기다리고 있나 (= 이미 옛 값을 읽고 UPDATE에서 막혔다)
    private fun someoneWaitsForRowLock(): Boolean = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
        Int::class.java,
    )!! > 0

    private val executor = Executors.newFixedThreadPool(20)

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
        lockSwitch.change(LockStrategy.OPTIMISTIC)
    }

    private fun <T> Future<T>.result(): Result<T> = try {
        Result.success(get(30, TimeUnit.SECONDS))
    } catch (e: ExecutionException) {
        Result.failure(e.cause!!)
    }

    // ───────────── 틀린 위치: 트랜잭션 안에서 잡고 푼다 ─────────────

    @Test
    fun `트랜잭션 안에서 락을 풀면 - 안전망 없이(NONE) 출금 하나가 사라진다`() {
        val account = fundedAccount(10_000)
        lockSwitch.change(LockStrategy.NONE)   // 버전 검사 없이 덮어쓰기 = "Redis 락만 믿는" 구현
        val released = CountDownLatch(1)

        // A: 락 → 출금(UPDATE 실행, 행 잠금은 커밋까지) → 락 해제 → B가 옛 값을 읽고 UPDATE에서 막힐 때까지 기다렸다 커밋
        val a = executor.submit {
            transactionTemplate.execute {
                withRedisLock(account) { withdraw(account, 1_000) }
                released.countDown()
                val deadline = System.currentTimeMillis() + 10_000
                while (!someoneWaitsForRowLock()) {
                    check(System.currentTimeMillis() < deadline) { "B가 행 잠금에서 기다리지 않았다" }
                    Thread.sleep(10)
                }
            }
        }
        // B: A가 락을 푼 뒤 들어온다. Redis 락은 잡히지만 A의 9,000은 아직 커밋 전 → 10,000을 읽는다
        val b = executor.submit {
            released.await(10, TimeUnit.SECONDS)
            transactionTemplate.execute { withRedisLock(account) { withdraw(account, 1_000) } }
        }

        assertTrue(a.result().isSuccess && b.result().isSuccess, "둘 다 성공으로 응답했다")
        assertEquals(9_000, balanceOf(account), "출금 2건이 성공했는데 잔액은 1건만 빠졌다: B가 A의 커밋 전 값(10,000)으로 덮었다")
    }

    @Test
    fun `트랜잭션 안에서 락을 풀면 - @Version 안전망(REDIS)이 늦은 커밋을 거절한다, 락이 뚫렸다는 증거`() {
        val account = fundedAccount(10_000)
        lockSwitch.change(LockStrategy.REDIS)
        val released = CountDownLatch(1)
        val bCommitted = CountDownLatch(1)

        // A: 락 → 출금(엔티티만 바뀜, UPDATE는 커밋 때) → 락 해제 → B가 먼저 커밋할 때까지 기다렸다 커밋
        val a = executor.submit {
            transactionTemplate.execute {
                withRedisLock(account) { withdraw(account, 1_000) }
                released.countDown()
                bCommitted.await(10, TimeUnit.SECONDS)
            }
        }
        val b = executor.submit {
            try {
                released.await(10, TimeUnit.SECONDS)
                transactionTemplate.execute { withRedisLock(account) { withdraw(account, 1_000) } }
            } finally {
                bCommitted.countDown()
            }
        }

        assertTrue(b.result().isSuccess)
        assertIs<OptimisticLockingFailureException>(a.result().exceptionOrNull(), "A의 커밋: UPDATE ... WHERE version = (B가 이미 올린 옛 버전) → 0행")
        assertEquals(9_000, balanceOf(account), "돈은 맞다 (A는 실패로 끝남). posting이라면 A를 재시도했을 것 → 재시도 지표가 0이 아니면 락이 뚫린 것")
    }

    // ───────────── 맞는 위치: 락이 트랜잭션 전체를 감싼다 ─────────────

    @Test
    fun `락이 트랜잭션을 감싸면 - 동시 출금 20건이 한 줄로 서서 전부 반영된다, 버전 충돌 0`() {
        val account = fundedAccount(10_000)
        lockSwitch.change(LockStrategy.REDIS)
        val start = CountDownLatch(1)

        // 락 → 트랜잭션 시작 → 출금 → 커밋 → 락 해제. 다음 사람은 커밋된 값만 본다
        val futures = List(20) {
            executor.submit {
                start.await()
                withRedisLock(account) { transactionTemplate.execute { withdraw(account, 100) } }
            }
        }
        start.countDown()
        val results = futures.map { it.result() }

        // 락 없이(OPTIMISTIC) 20건이 동시에 오면 재시도 없이는 대부분 버전 충돌로 실패한다 (LockStrategyConcurrencyTest 핫 계좌)
        assertTrue(results.all { it.isSuccess }, "실패: ${results.mapNotNull { it.exceptionOrNull()?.javaClass?.simpleName }}")
        assertEquals(8_000, balanceOf(account))
    }
}
