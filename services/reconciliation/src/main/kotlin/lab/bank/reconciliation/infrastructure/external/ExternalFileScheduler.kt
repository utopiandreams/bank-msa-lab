package lab.bank.reconciliation.infrastructure.external

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.reconciliation.application.ExternalFileReceiver
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/** 매일 새벽 전날 외부 정산 파일을 받아 온다. 실패하면 로그만: 다시 받으려면 내부 API (ExternalFileController) */
@Component
@ConditionalOnProperty(prefix = "reconciliation.external", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ExternalFileScheduler(private val receiver: ExternalFileReceiver) {

    // 01:00: 외부 기관이 전날 파일을 마감해 내놓는 시각이라고 가정 (우리 추출 00:05보다 늦게)
    @Scheduled(cron = "\${reconciliation.external.cron:0 0 1 * * *}", zone = "Asia/Seoul")
    fun receiveYesterday() {
        val yesterday = LocalDate.now(ExtractCompletedEvent.BUSINESS_ZONE).minusDays(1)
        try {
            receiver.receive(yesterday)
        } catch (e: Exception) {
            log.error("외부 정산 파일 수신 실패 date={} (내부 API로 다시 받기)", yesterday, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExternalFileScheduler::class.java)
    }
}
