package lab.bank.transfer.infrastructure.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * 끝나지 않은 송금 지표 (2026-11-26, "은행 운영" 대시보드). 결과 불명·보상 중인 송금이 몇 건이고 얼마나 오래됐나를
 * DB에 붙지 않고 그래프로 보려고.
 *
 *   transfer_unresolved{kind,status,age}               끝나지 않은 송금 수. age = 접수(created_at)부터 지금까지 구간
 *                                                      (lt1m · 1m_10m · 10m_1h · gt1h)
 *   transfer_unresolved_oldest_age_seconds{kind,status} 상태별로 가장 오래된 송금의 나이
 *
 * 읽는 법: PROCESSING·WITHDRAW_REQUESTED 같은 의도 상태는 정상 송금도 수십 ms 동안 거친다 → lt1m에 몇 건은 "처리 중"이다.
 * **해소 기준 시간(transfer.resolution.check-after 등)보다 오래된 칸이 표류**다. 해소 일꾼이 돌면 다음 회차에 줄어야 하고,
 * 1m_10m 이상 칸이 계속 쌓이면 해소가 막혔다(상대 장애, head-of-line).
 *
 * 나이는 updated_at이 아니라 created_at 기준: 고객이 기다린 시간이다 (updated_at은 해소 시도마다 바뀐다).
 * 쿼리의 상태 목록은 해소용 부분 인덱스(ix_transfer_unresolved, V8)의 조건과 같다 → 인덱스만 훑는다 (전체 송금 테이블을 안 읽음).
 * REQUESTED는 그 인덱스에 없고 접수 트랜잭션 안에서 바로 다음 상태로 가서 뺐다.
 *
 * 15초마다 다시 센다(ReconMetrics와 같은 방식). 파드마다 같은 테이블을 보므로 대시보드·경보에서는 sum이 아니라 max로 본다.
 * DB를 못 읽으면 지표를 비운다: 빈 칸 = "모름" (0으로 두면 "표류 없음"으로 오해한다)
 */
@Component
class UnresolvedTransferMetrics(
    registry: MeterRegistry,
    dataSource: DataSource,
) {
    private val jdbc = JdbcTemplate(dataSource)

    private val count = MultiGauge.builder("transfer.unresolved")
        .description("끝나지 않은 송금 수 (접수부터 지난 시간 구간별, 파드마다 같은 값이라 max로 볼 것)")
        .register(registry)
    private val oldest = MultiGauge.builder("transfer.unresolved.oldest.age")
        .description("상태별 가장 오래된 끝나지 않은 송금의 나이 (접수부터)")
        .baseUnit("seconds")
        .register(registry)

    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    fun refresh() {
        val rows = try {
            jdbc.query(SQL) { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getDouble(5)) }
        } catch (e: Exception) {
            log.warn("끝나지 않은 송금 조회 실패: {}", e.message)
            emptyList()
        }
        count.register(rows.map { MultiGauge.Row.of(Tags.of("kind", it.kind, "status", it.status, "age", it.age), it.count) }, true)
        oldest.register(
            rows.groupBy { it.kind to it.status }.map { (key, group) ->
                MultiGauge.Row.of(Tags.of("kind", key.first, "status", key.second), group.maxOf { it.oldestSeconds })
            },
            true,   // 사라진 조합(다 풀린 상태)은 지표에서도 지운다
        )
    }

    private data class Row(val kind: String, val status: String, val age: String, val count: Long, val oldestSeconds: Double)

    companion object {
        private val log = LoggerFactory.getLogger(UnresolvedTransferMetrics::class.java)

        private val SQL = """
            SELECT kind, status,
                   CASE WHEN created_at > now() - interval '1 minute'   THEN 'lt1m'
                        WHEN created_at > now() - interval '10 minutes' THEN '1m_10m'
                        WHEN created_at > now() - interval '1 hour'     THEN '10m_1h'
                        ELSE 'gt1h' END AS age,
                   count(*),
                   EXTRACT(EPOCH FROM now() - min(created_at))::float8
            FROM transfer.transfer
            WHERE status IN ('PROCESSING', 'UNKNOWN',
                             'WITHDRAW_REQUESTED', 'WITHDRAWN', 'EXTERNAL_BANK_REQUESTED', 'WITHDRAW_CANCEL_REQUESTED')
            GROUP BY 1, 2, 3
        """.trimIndent()
    }
}
