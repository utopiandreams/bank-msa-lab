package lab.bank.core.account.application.balance

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * application.yaml의 core.lock.* — 파드가 뜰 때의 기본값. 실행 중 변경은 LockStrategySwitch(관리 포트 API)로
 *
 * @property strategy              시작 전략. 재시작하면 이 값으로 돌아온다 (실험하다 잊어도 안전한 쪽으로)
 * @property optimisticMaxAttempts 낙관적 락 충돌 시 트랜잭션을 몇 번까지 시도하나 (첫 시도 포함). 1이면 재시도 없음
 * @property redis                 REDIS 전략의 분산 락 시간 (account/infrastructure/lock/RedisAccountLocks)
 */
@ConfigurationProperties(prefix = "core.lock")
data class LockProperties(
    val strategy: LockStrategy = LockStrategy.OPTIMISTIC,
    val optimisticMaxAttempts: Int = 3,
    val redis: Redis = Redis(),
) {
    init {
        require(optimisticMaxAttempts >= 1) { "core.lock.optimistic-max-attempts는 1 이상: $optimisticMaxAttempts" }
    }

    /**
     * @property waitTime  잠금을 기다리는 상한. 넘으면 포기하고 503. **부르는 쪽 타임아웃보다 짧아야 한다**:
     *                     transfer → core read-timeout이 3초라, 대기가 그보다 길면 transfer는 "결과 불명"으로 끊었는데
     *                     core는 뒤늦게 잠금을 잡아 처리하는 일이 생긴다 (끊긴 요청이 나중에 성공 = UNKNOWN의 원인)
     * @property leaseTime 잡은 뒤 자동으로 풀리는 시간 (주면 Redisson 워치독이 꺼진다). 처리가 이보다 길면 락이 먼저 풀린다
     *                     → 그때는 @Version 안전망이 잡는다. 평소 처리(수십 ms)보다 넉넉하게
     */
    data class Redis(
        val waitTime: Duration = Duration.ofSeconds(1),
        val leaseTime: Duration = Duration.ofSeconds(10),
    )
}
