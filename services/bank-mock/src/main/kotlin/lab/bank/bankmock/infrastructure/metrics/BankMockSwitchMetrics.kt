package lab.bank.bankmock.infrastructure.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import lab.bank.bankmock.application.BankMockProperties
import lab.bank.bankmock.application.BankMockSwitches
import org.springframework.stereotype.Component

/**
 * 지금 외부 은행이 어떻게 설정돼 있나 (2026-11-26, "은행 운영" 대시보드).
 * 스위치를 재시작 없이 바꾸게 되면서 "지금 장애가 걸려 있나"를 화면에서 바로 봐야 한다: 그래프가 꺾인 시점과
 * 스위치를 바꾼 시점이 같은 축에 보이면 원인이 한눈에 읽힌다 (12주 차 패널 "스위치 바꾼 시점 마커"의 재료).
 *
 *   bankmock_switch{name}  name = reject_rate_percent · processing_delay_seconds · processing_delay_percent ·
 *                                 response_delay_seconds · response_delay_percent · processing_deadline_seconds · lookup_lies_not_found(0/1)
 * 게이지는 긁을 때마다 현재 값을 읽는다 (BankMockSwitches.current, volatile)
 */
@Component
class BankMockSwitchMetrics(switches: BankMockSwitches, registry: MeterRegistry) {

    init {
        val values: Map<String, (BankMockProperties) -> Double> = mapOf(
            "reject_rate_percent" to { it.rejectRatePercent.toDouble() },
            "processing_delay_seconds" to { it.processingDelay.toMillis() / 1000.0 },
            "processing_delay_percent" to { it.processingDelayPercent.toDouble() },
            "response_delay_seconds" to { it.responseDelay.toMillis() / 1000.0 },
            "response_delay_percent" to { it.responseDelayPercent.toDouble() },
            "processing_deadline_seconds" to { it.processingDeadline.toMillis() / 1000.0 },
            "lookup_lies_not_found" to { if (it.lookupLiesNotFound) 1.0 else 0.0 },
        )
        values.forEach { (name, read) ->
            Gauge.builder("bankmock.switch", switches) { read(it.current) }
                .description("외부 은행 실험 스위치의 현재 값")
                .tag("name", name)
                .register(registry)
        }
    }
}
