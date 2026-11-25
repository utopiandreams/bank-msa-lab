package lab.bank.transfer.infrastructure.resolution

import lab.bank.transfer.application.InterbankResolutionUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 타행 결과 불명 해소 스케줄러 (인바운드 어댑터). 당행 UnknownResolutionScheduler와 같은 모양, 같은 스위치·주기.
 * 당행과 따로 두는 이유: 유스케이스가 다르고, 한쪽 회차가 느려도(외부 지연) 다른 쪽을 막지 않게.
 * 단, Spring 기본 스케줄러 스레드는 1개라 두 @Scheduled가 같은 스레드에서 번갈아 돈다 → 진짜로 안 막으려면 풀을 늘려야 한다
 * (spring.task.scheduling.pool.size). 지금은 멈춘 송금이 거의 0건이라 그대로 둔다
 */
@Component
@ConditionalOnProperty(prefix = "transfer.resolution", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class InterbankResolutionScheduler(
    private val resolutionUseCase: InterbankResolutionUseCase,
) {

    @Scheduled(fixedDelayString = "\${transfer.resolution.poll-interval:5s}")
    fun resolve() {
        try {
            val resolved = resolutionUseCase.resolveStuck()
            if (resolved > 0) log.info("타행 결과 불명 해소: {}건 확정", resolved)
        } catch (e: Exception) {
            log.warn("타행 결과 불명 해소 회차 실패 (다음 회차에 재시도): {}", e.toString())
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(InterbankResolutionScheduler::class.java)
    }
}
