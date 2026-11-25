package lab.bank.reconciliation.infrastructure.worker

import lab.bank.reconciliation.application.ReconRunService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 대사 일꾼 (인바운드 어댑터): 주기적으로 "두 출처가 다 도착했는데 아직 안 돈 날짜"를 찾아 돌린다.
 * 재시작해도 그대로 이어진다: 진행 상태가 이벤트가 아니라 DB(인박스·회차)에 있어서 (결정 15)
 */
@Component
@ConditionalOnProperty(prefix = "reconciliation.worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReconWorker(
    private val runService: ReconRunService,
) {

    @Scheduled(fixedDelayString = "\${reconciliation.worker.poll-interval:60s}")
    fun runPending() {
        try {
            val ran = runService.runPending()
            if (ran > 0) log.info("대사 회차 {}건 실행", ran)
        } catch (e: Exception) {
            log.warn("대사 일꾼 회차 실패 (다음 회차에 다시): {}", e.toString())
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReconWorker::class.java)
    }
}
