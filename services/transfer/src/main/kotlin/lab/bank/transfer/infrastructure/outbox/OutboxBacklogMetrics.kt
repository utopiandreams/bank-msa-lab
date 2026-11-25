package lab.bank.transfer.infrastructure.outbox

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import lab.bank.transfer.infrastructure.persistence.OutboxBacklogRow
import lab.bank.transfer.infrastructure.persistence.OutboxJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Outbox 적체 지표 (2026-11-25, 6주 차에 남겨 둔 것). Kafka가 죽으면 송금은 계속 되고 이벤트만 outbox에 쌓인다 (6주 차 설계 의도).
 * 그 "쌓임"이 지금까지는 DB를 직접 봐야 보였다 → 그래프로:
 * - transfer_outbox_unpublished: 아직 안 나간 행 수. 평소 0~몇 건, 브로커 장애 동안 계단처럼 오르고 복구되면 뚝 떨어진다
 * - transfer_outbox_oldest_unpublished_age_seconds: 가장 오래 기다린 행의 나이. 평소 1초 안팎(폴링 간격).
 *   행 수보다 이게 경보에 맞다: 송금이 적은 새벽엔 행 수가 작아도 "10분째 안 나감"이면 장애다 (알림이 그만큼 늦다)
 *
 * Prometheus가 긁을 때(15초마다) 쿼리 한 번. 두 게이지가 같은 결과를 쓰도록 2초 동안 재사용한다.
 * 파드마다 같은 테이블을 보므로 파드가 여럿이면 값이 겹친다 → 대시보드·경보에서는 sum이 아니라 max로 본다.
 * DB에 못 붙으면 NaN (빈 칸) = "모름". 0으로 두면 "적체 없음"으로 오해한다
 */
@Component
class OutboxBacklogMetrics(
    private val repository: OutboxJpaRepository,
    registry: MeterRegistry,
) {
    private var cached: OutboxBacklogRow? = null
    private var cachedAt: Instant = Instant.EPOCH

    init {
        Gauge.builder("transfer.outbox.unpublished") { read()?.unpublished?.toDouble() ?: Double.NaN }
            .description("아직 Kafka로 나가지 않은 outbox 행 수 (파드마다 같은 값, max로 볼 것)")
            .register(registry)
        Gauge.builder("transfer.outbox.oldest.unpublished.age") { read()?.oldestAgeSeconds ?: Double.NaN }
            .description("가장 오래된 미발행 outbox 행의 나이 (없으면 0)")
            .baseUnit("seconds")
            .register(registry)
    }

    @Synchronized
    private fun read(): OutboxBacklogRow? {
        val now = Instant.now()
        if (Duration.between(cachedAt, now) < REUSE_FOR) return cached
        cached = try {
            repository.backlog()
        } catch (e: Exception) {
            log.warn("outbox 적체 조회 실패: {}", e.message)
            null
        }
        cachedAt = now
        return cached
    }

    companion object {
        private val log = LoggerFactory.getLogger(OutboxBacklogMetrics::class.java)
        private val REUSE_FOR: Duration = Duration.ofSeconds(2)
    }
}
