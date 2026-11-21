package lab.bank.transfer.infrastructure.outbox

import lab.bank.transfer.infrastructure.persistence.OutboxJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * 발행 완료 outbox 행 정리 (일일 배치). 발행한 지 retention(7일)이 지난 행만 지운다.
 *
 * 락이 없다: 파드마다 같은 시각에 돌지만 "N일 전보다 오래된 발행 완료 행 삭제"는 두 번 돌아도 결과가 같다 (멱등).
 * 배치는 락으로 한 곳에서만 돌게 하기보다 여러 번 돌아도 안전하게 만드는 쪽이 단순하다.
 *
 * 미발행 행(published_at NULL)은 절대 지우지 않는다: 오래 안 나간 이벤트는 지울 대상이 아니라 들여다볼 대상이다 (릴레이 장애, 깨진 행).
 */
@Component
class OutboxCleanup(
    private val repository: OutboxJpaRepository,
    private val transactionTemplate: TransactionTemplate,
    private val properties: OutboxProperties,
) {

    @Scheduled(cron = "\${outbox.cleanup.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    fun cleanup() {
        val before = Instant.now().minus(properties.cleanup.retention)
        val deleted = transactionTemplate.execute { repository.deletePublishedBefore(before) } ?: 0
        log.info("outbox 정리: 발행 완료 {}건 삭제 (기준 {} 이전)", deleted, before)
    }

    companion object {
        private val log = LoggerFactory.getLogger(OutboxCleanup::class.java)
    }
}
