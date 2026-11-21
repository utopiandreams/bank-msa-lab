package lab.bank.core.account.infrastructure.lock

import lab.bank.core.account.application.AccountLocks
import lab.bank.core.account.application.balance.LockProperties
import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.account.application.balance.LockStrategySwitch
import lab.bank.core.account.domain.exception.AccountLockTimeoutException
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * REDIS·REDIS_SPIN 전략일 때만 Redis 락으로 감싼다. 다른 전략이면 block을 그냥 실행 (Redis에 아무것도 안 보냄).
 *
 * 두 락의 차이는 **못 잡았을 때 기다리는 방식**뿐이다 (잡기·풀기는 둘 다 Lua 스크립트 한 번):
 * - REDIS (getLock, pub/sub): 락 채널을 구독하고 잠든다. 쥔 쪽이 unlock하면 Redis가 "풀렸다" 메시지를 보내 깨워 준다
 * - REDIS_SPIN (getSpinLock): 잠깐 자고 다시 시도한다. 기본 백오프 1ms에서 2배씩, 최대 128ms. 기다리는 동안 계속 Redis에 묻는다
 *
 * "N초 기다리다 포기"는 타이머가 필요 없다: tryLock(waitTime, ...)의 waitTime이 그 상한이다. 넘으면 false → 예외로 바꾼다.
 */
@Component
class RedisAccountLocks(
    private val redissonClient: RedissonClient,
    private val lockSwitch: LockStrategySwitch,
    private val properties: LockProperties,
) : AccountLocks {

    override fun <T> getAccountLock(vararg accountIds: UUID, block: () -> T): T {
        val strategy = lockSwitch.strategy   // 한 번만 읽는다: 도중에 스위치가 바뀌어도 잡을 때와 풀 때가 같은 종류
        if (strategy != LockStrategy.REDIS && strategy != LockStrategy.REDIS_SPIN) return block()

        val held = ArrayList<RLock>(accountIds.size)
        try {
            acquireAll(strategy, accountIds.distinct().sorted(), held)
            return block()   // 이 안에서 트랜잭션이 열리고 **커밋까지** 끝난다. 그 뒤에야 아래 finally에서 푼다
        } finally {
            // 잡은 반대 순서로 푼다. 리스 시간이 지나 이미 풀렸으면(처리가 너무 길었음) 내 락이 아니다 → 건드리지 않는다
            held.asReversed().forEach { if (it.isHeldByCurrentThread) it.unlock() }
        }
    }

    // 대기 상한은 계좌 수와 상관없이 전체에 한 번: 두 번째 계좌는 첫 번째에서 쓰고 남은 시간만 기다린다
    private fun acquireAll(strategy: LockStrategy, sortedIds: List<UUID>, held: MutableList<RLock>) {
        val waitMillis = properties.redis.waitTime.toMillis()
        val leaseMillis = properties.redis.leaseTime.toMillis()
        val deadline = System.currentTimeMillis() + waitMillis
        for (accountId in sortedIds) {
            val lock = lockOf(strategy, accountId)
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            val acquired = try {
                lock.tryLock(remaining, leaseMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()   // 인터럽트 표시를 되살려 둔다 (삼키면 종료 신호가 사라진다)
                throw AccountLockTimeoutException(accountId)
            }
            if (!acquired) throw AccountLockTimeoutException(accountId)   // 앞에서 잡은 것은 호출자의 finally가 푼다
            held += lock
        }
    }

    // 키는 같다: 두 전략을 오가도 같은 계좌는 같은 키 (단, 종류가 다른 락끼리는 서로를 모른다 → 스위치는 부하가 없을 때 바꾼다)
    private fun lockOf(strategy: LockStrategy, accountId: UUID): RLock {
        val key = "$PREFIX$accountId"
        return if (strategy == LockStrategy.REDIS_SPIN) redissonClient.getSpinLock(key) else redissonClient.getLock(key)
    }

    companion object {
        private const val PREFIX = "lock:account:"
    }
}
