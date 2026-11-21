package lab.bank.core.account.application

import java.util.UUID

/**
 * 트랜잭션 **바깥**을 한 겹 감싸는 계좌 잠금 (7주 차). 트랜잭션은 부르는 쪽(posting)이 block 안에서 연다.
 * 잠글지 말지·어떻게 잠글지는 account가 정한다: 락 전략이 REDIS(pub/sub)·REDIS_SPIN일 때만 Redis 락, 나머지는 block을 그냥 실행.
 * 구현: account/infrastructure/lock/RedisAccountLocks
 */
interface AccountLocks {

    /**
     * 계좌 여러 개(송금)는 ID 순서로 잡는다 → 반대 방향 송금끼리 서로를 기다리는 데드락이 없다 (pitfalls 32).
     * 대기 상한 안에 다 못 잡으면 AccountLockTimeoutException, block은 실행되지 않는다
     */
    fun <T> getAccountLock(vararg accountIds: UUID, block: () -> T): T
}
