package lab.bank.core.extract.infrastructure

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.core.extract.application.CoreDailyExtractService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 매일 새벽 전날(영업일) 분을 추출한다 (인바운드 어댑터). 실패하면 로그만 남기고 끝: 대사 서비스의 기한 경보가 "안 왔다"를 잡는다 (결정 13).
 * 파드마다 돈다: 파드가 둘이면 같은 날짜가 두 번 추출되는데, 파일이 둘 생기고 대사가 마지막 것을 쓰니 해가 없다 (멱등)
 */
@Component
@ConditionalOnProperty(prefix = "extract", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DailyExtractScheduler(
    private val extractService: CoreDailyExtractService,
) {

    // 00:05: 자정 직후의 늦은 커밋이 끝날 여유. 그래도 전날 송금이 오늘 상태가 바뀌면 오늘 파일에 실린다 (결정 21)
    @Scheduled(cron = "\${extract.cron:0 5 0 * * *}", zone = "Asia/Seoul")
    fun extractYesterday() {
        val yesterday = LocalDate.now(ExtractCompletedEvent.BUSINESS_ZONE).minusDays(1)
        try {
            extractService.extract(yesterday)
        } catch (e: Exception) {
            log.error("대사 추출 실패 date={} (대사 서비스 기한 경보로 드러난다. 내부 API로 다시 추출)", yesterday, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DailyExtractScheduler::class.java)
    }
}
