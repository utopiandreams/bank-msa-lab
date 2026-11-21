package lab.bank.core.account.application.balance

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * application.yaml의 core.lock.* — 파드가 뜰 때의 기본값. 실행 중 변경은 LockStrategySwitch(관리 포트 API)로
 *
 * @property strategy              시작 전략. 재시작하면 이 값으로 돌아온다 (실험하다 잊어도 안전한 쪽으로)
 * @property optimisticMaxAttempts 낙관적 락 충돌 시 트랜잭션을 몇 번까지 시도하나 (첫 시도 포함). 1이면 재시도 없음
 */
@ConfigurationProperties(prefix = "core.lock")
data class LockProperties(
    val strategy: LockStrategy = LockStrategy.OPTIMISTIC,
    val optimisticMaxAttempts: Int = 3,
) {
    init {
        require(optimisticMaxAttempts >= 1) { "core.lock.optimistic-max-attempts는 1 이상: $optimisticMaxAttempts" }
    }
}
