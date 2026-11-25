package lab.bank.bankmock.api

import lab.bank.bankmock.application.BankMockProperties
import lab.bank.bankmock.application.BankMockSwitches
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 외부 은행 실험 스위치 API (2026-11-26). core와 같은 방식: actuator라 **관리 포트(8081)**에만 열린다 (core ExperimentsEndpoint 주석).
 *
 *   GET  :8081/actuator/experiments
 *   POST :8081/actuator/experiments  {"responseDelay":"5s","responseDelayPercent":2}   ← 보낸 필드만 바뀐다
 *   POST :8081/actuator/experiments  {"reset":true}                                    ← 설정값(시작값)으로
 *
 * 파라미터가 Kotlin nullable이면 actuator가 "안 보내도 되는 필드"로 본다 (Boot 4는 Spring Nullness로 판단, jar 확인)
 * 재시작하면 설정값으로 돌아온다. 바꿀 때마다 WARN 로그 = 실험 타임라인의 기준점 (Loki에서 "실험 스위치 변경"으로 찾는다)
 */
@Component
@Endpoint(id = "experiments")
class ExperimentsEndpoint(
    private val switches: BankMockSwitches,
    private val initial: BankMockProperties,
) {

    @ReadOperation
    fun read(): BankMockProperties = switches.current

    @WriteOperation
    fun change(
        rejectRatePercent: Int?,
        processingDelay: Duration?,
        processingDelayPercent: Int?,
        responseDelay: Duration?,
        responseDelayPercent: Int?,
        lookupLiesNotFound: Boolean?,
        reset: Boolean?,
    ): Map<String, BankMockProperties> {
        val (previous, next) = try {
            switches.change { now ->
                val base = if (reset == true) initial else now
                base.copy(
                    rejectRatePercent = rejectRatePercent ?: base.rejectRatePercent,
                    processingDelay = processingDelay ?: base.processingDelay,
                    processingDelayPercent = processingDelayPercent ?: base.processingDelayPercent,
                    responseDelay = responseDelay ?: base.responseDelay,
                    responseDelayPercent = responseDelayPercent ?: base.responseDelayPercent,
                    lookupLiesNotFound = lookupLiesNotFound ?: base.lookupLiesNotFound,
                )
            }
        } catch (e: IllegalArgumentException) {
            throw InvalidEndpointRequestException(e.message ?: "잘못된 값", e.message ?: "잘못된 값")   // 400
        }
        log.warn("실험 스위치 변경: {} → {}", previous, next)
        return mapOf("current" to next, "previous" to previous)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExperimentsEndpoint::class.java)
    }
}
