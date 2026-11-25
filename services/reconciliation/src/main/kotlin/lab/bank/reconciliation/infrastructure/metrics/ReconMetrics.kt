package lab.bank.reconciliation.infrastructure.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 대사 지표 (ADR-0006 결정 13·14, 경보 규칙: deploy/platform/monitoring/reconciliation-rules.yaml).
 *   reconciliation_items{pair,kind,status}           살아 있는 예외 항목 수 (OPEN = 이월, ESCALATED = 경보)
 *   reconciliation_last_arrival_seconds{source}      출처별 마지막 추출 도착 시각 (epoch 초) → "안 왔다" 기한 경보(dead man's switch)
 *   reconciliation_last_done_seconds{pair}           쌍별 마지막 대사 완료 시각
 * 회차마다가 아니라 30초마다 DB에서 다시 센다: 지표가 DB와 어긋날 일이 없고, 파드가 재시작해도 바로 맞는 값
 */
@Component
class ReconMetrics(
    registry: MeterRegistry,
    private val jdbc: JdbcClient,
) {
    private val items = MultiGauge.builder("reconciliation.items").description("살아 있는 대사 예외 항목 수").register(registry)
    private val lastArrival = MultiGauge.builder("reconciliation.last.arrival.seconds").description("출처별 마지막 추출 도착 시각 (epoch 초)").register(registry)
    private val lastDone = MultiGauge.builder("reconciliation.last.done.seconds").description("쌍별 마지막 대사 완료 시각 (epoch 초)").register(registry)

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    fun refresh() {
        items.register(
            jdbc.sql("SELECT pair, kind, status, count(*) FROM reconciliation.recon_item WHERE status IN ('OPEN', 'ESCALATED') GROUP BY 1, 2, 3")
                .query { rs, _ -> MultiGauge.Row.of(Tags.of("pair", rs.getString(1), "kind", rs.getString(2), "status", rs.getString(3)), rs.getLong(4)) }
                .list(),
            true,   // 사라진 조합(풀린 항목)은 지표에서도 지운다
        )
        lastArrival.register(
            jdbc.sql("SELECT source, extract(epoch FROM max(received_at)) FROM reconciliation.extract_arrival GROUP BY 1")
                .query { rs, _ -> MultiGauge.Row.of(Tags.of("source", rs.getString(1)), rs.getDouble(2)) }
                .list(),
            true,
        )
        lastDone.register(
            jdbc.sql("SELECT pair, extract(epoch FROM max(finished_at)) FROM reconciliation.recon_run WHERE status = 'DONE' GROUP BY 1")
                .query { rs, _ -> MultiGauge.Row.of(Tags.of("pair", rs.getString(1)), rs.getDouble(2)) }
                .list(),
            true,
        )
    }
}
