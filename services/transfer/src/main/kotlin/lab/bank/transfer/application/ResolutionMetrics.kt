package lab.bank.transfer.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 해소 일꾼의 결과 지표 (2026-11-26, "은행 운영" 대시보드):
 *   transfer_resolution_total{kind, result, to}
 *     result = resolved (끝 상태로 확정) | still_stuck (조회·취소 실패, 다음 회차에 다시)
 *     to     = 확정된 끝 상태 (COMPLETED · FAILED · CANCELLED), still_stuck이면 none
 * "결과 불명이 생기는 속도"(transfer_unresolved 증가)와 나란히 두면 해소가 따라잡고 있는지 보인다.
 * 송금 ID·이전 상태는 로그에 있다 (지표 라벨에 넣으면 시계열이 송금 수만큼 늘어난다).
 *
 * Micrometer는 SLF4J 같은 파사드라 application 계층에서 바로 쓴다 (포트로 감싸지 않음: 바꿔 끼울 일이 없고 테스트에도 영향 없음).
 * 커밋된 뒤에 부른다. 조합은 시작할 때 0으로 만들어 둔다 (pitfalls 41)
 */
@Component
class ResolutionMetrics(private val registry: MeterRegistry) {

    init {
        listOf("DOMESTIC" to listOf("COMPLETED", "FAILED"), "INTERBANK" to listOf("COMPLETED", "FAILED", "CANCELLED"))
            .forEach { (kind, finals) ->
                finals.forEach { counter(kind, RESOLVED, it) }
                counter(kind, STILL_STUCK, NONE)
            }
    }

    fun resolved(kind: String, to: String) = counter(kind, RESOLVED, to).increment()

    fun stillStuck(kind: String) = counter(kind, STILL_STUCK, NONE).increment()

    private fun counter(kind: String, result: String, to: String): Counter =
        Counter.builder("transfer.resolution")
            .description("해소 일꾼이 잡은 송금의 결과 (커밋된 것만)")
            .tags("kind", kind, "result", result, "to", to)
            .register(registry)

    companion object {
        const val DOMESTIC = "DOMESTIC"
        const val INTERBANK = "INTERBANK"
        private const val RESOLVED = "resolved"
        private const val STILL_STUCK = "still_stuck"
        private const val NONE = "none"
    }
}
