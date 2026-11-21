package lab.bank.core.account.balance

import lab.bank.core.RedisTestcontainerConfiguration
import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.account.application.AccountLocks
import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.account.application.balance.LockStrategySwitch
import lab.bank.core.account.domain.exception.AccountLockTimeoutException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** RedisAccountLocks 단독: 전략에 따라 잠그나, 못 잡으면 대기 상한(core.lock.redis.wait-time = 1초)에서 포기하나 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, RedisTestcontainerConfiguration::class)
class RedisAccountLocksTest {

    @Autowired lateinit var accountLocks: AccountLocks
    @Autowired lateinit var lockSwitch: LockStrategySwitch
    @Autowired lateinit var redisson: RedissonClient

    private val executor = Executors.newSingleThreadExecutor()

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
        lockSwitch.change(LockStrategy.OPTIMISTIC)
    }

    private fun key(accountId: UUID) = "lock:account:$accountId"
    // 락 종류와 상관없이 "Redis에 그 키가 있나"로 본다
    private fun lockKeyExists(accountId: UUID) = redisson.keys.countExists(key(accountId)) > 0
    private fun lockOf(strategy: LockStrategy, accountId: UUID): RLock =
        if (strategy == LockStrategy.REDIS_SPIN) redisson.getSpinLock(key(accountId)) else redisson.getLock(key(accountId))

    @Test
    fun `REDIS 계열이 아니면 잠그지 않고 그냥 실행한다 - Redis에 키가 안 생긴다`() {
        val accountId = UUID.randomUUID()
        lockSwitch.change(LockStrategy.PESSIMISTIC)

        val held = accountLocks.getAccountLock(accountId) { lockKeyExists(accountId) }

        assertFalse(held)
    }

    @ParameterizedTest
    @EnumSource(value = LockStrategy::class, names = ["REDIS", "REDIS_SPIN"])
    fun `block을 실행하는 동안 계좌마다 락을 쥐고, 끝나면 푼다`(strategy: LockStrategy) {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        lockSwitch.change(strategy)

        val heldInside = accountLocks.getAccountLock(a, b) { lockKeyExists(a) && lockKeyExists(b) }

        assertTrue(heldInside)
        assertFalse(lockKeyExists(a) || lockKeyExists(b), "끝나면 풀려야 다음 요청이 들어온다")
    }

    @ParameterizedTest
    @EnumSource(value = LockStrategy::class, names = ["REDIS", "REDIS_SPIN"])
    fun `다른 쪽이 쥐고 있으면 대기 상한(1초) 뒤 포기하고 예외 - block은 실행되지 않는다`(strategy: LockStrategy) {
        val accountId = UUID.randomUUID()
        lockSwitch.change(strategy)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit {   // 다른 스레드(= 다른 요청)가 같은 계좌 락을 쥐고 놓지 않는다
            val other = lockOf(strategy, accountId)
            other.lock()
            holding.countDown()
            release.await(10, TimeUnit.SECONDS)
            other.unlock()
        }
        holding.await(5, TimeUnit.SECONDS)
        var ran = false

        val elapsed = measureTime {
            assertFailsWith<AccountLockTimeoutException> { accountLocks.getAccountLock(accountId) { ran = true } }
        }
        release.countDown()

        assertFalse(ran)
        assertTrue(elapsed.inWholeMilliseconds in 900..2_500, "대기 상한 근처에서 포기: $elapsed")
    }

    @Test
    fun `두 번째 계좌에서 포기하면 먼저 잡은 첫 번째도 풀어 준다`() {
        val (first, second) = listOf(UUID.randomUUID(), UUID.randomUUID()).sorted()   // ID 순서로 잡는다
        lockSwitch.change(LockStrategy.REDIS)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.submit {
            val other = lockOf(LockStrategy.REDIS, second)
            other.lock()
            holding.countDown()
            release.await(10, TimeUnit.SECONDS)
            other.unlock()
        }
        holding.await(5, TimeUnit.SECONDS)

        assertFailsWith<AccountLockTimeoutException> { accountLocks.getAccountLock(second, first) { } }
        release.countDown()

        assertFalse(lockKeyExists(first), "첫 번째를 쥔 채 남으면 그 계좌는 리스 시간(10초) 동안 아무도 못 쓴다")
    }

    @Test
    fun `block이 예외를 던져도 락은 풀린다`() {
        val accountId = UUID.randomUUID()
        lockSwitch.change(LockStrategy.REDIS)

        assertFailsWith<IllegalStateException> { accountLocks.getAccountLock<Unit>(accountId) { error("업무 실패") } }

        assertFalse(lockKeyExists(accountId))
    }
}
