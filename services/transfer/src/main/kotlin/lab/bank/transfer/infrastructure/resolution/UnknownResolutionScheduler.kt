package lab.bank.transfer.infrastructure.resolution

import lab.bank.transfer.application.TransferResolutionUseCase
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 결과 불명 해소 스케줄러 (인바운드 어댑터). 시간이 되면 유스케이스를 부르기만 한다. 판단·트랜잭션은 유스케이스 몫.
 * transfer 파드마다 하나씩 돈다. 여러 대가 같은 송금을 잡지 않는 건 유스케이스의 SKIP LOCKED가 맡는다.
 *
 * infrastructure → application 호출이 괜찮은 이유: 헥사고날에서 의존은 바깥 → 안쪽으로만 향하면 된다.
 * 컨트롤러(api)가 유스케이스를 부르듯 스케줄러도 인바운드 어댑터다. 반대(application이 이 클래스를 아는 것)가 금지.
 */
@Component
@ConditionalOnProperty(prefix = "transfer.resolution", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class UnknownResolutionScheduler(
    private val resolutionUseCase: TransferResolutionUseCase,
) {

    // fixedDelay: 앞 회차가 끝난 뒤부터 잰다 → 한 파드 안에서 회차가 겹치지 않는다
    @Scheduled(fixedDelayString = "\${transfer.resolution.poll-interval:5s}")
    fun resolve() {
        try {
            val resolved = resolutionUseCase.resolveStuck()
            if (resolved > 0) log.info("결과 불명 해소: {}건 확정", resolved)
        } catch (e: Exception) {
            // 다음 회차에 다시. 스케줄러 스레드를 죽이지 않는다 (Spring은 예외가 나도 다음 회차를 돌리지만 로그는 남긴다)
            log.warn("결과 불명 해소 회차 실패 (다음 회차에 재시도): {}", e.toString())
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(UnknownResolutionScheduler::class.java)
    }
}
