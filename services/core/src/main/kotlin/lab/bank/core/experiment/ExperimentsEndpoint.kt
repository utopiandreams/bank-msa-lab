package lab.bank.core.experiment

import lab.bank.core.account.application.balance.LockStrategy
import lab.bank.core.account.application.balance.LockStrategySwitch
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.stereotype.Component

/**
 * 실험 스위치 API (7주 차, 12주 차 chaos-control·패널이 부를 입구). actuator 엔드포인트라 **관리 포트(8081)**에만 열린다.
 *
 *   GET  :8081/actuator/experiments                                     → {"lockStrategy":"OPTIMISTIC"}
 *   POST :8081/actuator/experiments  {"lockStrategy":"PESSIMISTIC"}    → {"lockStrategy":"PESSIMISTIC","previous":"OPTIMISTIC"}
 *
 * 왜 일반 컨트롤러(/internal/...)가 아니라 actuator인가:
 * - 8081은 Service·Ingress에 없다 → gateway를 통해 인터넷에서 부를 길이 원천적으로 없다 (동작을 바꾸는 API라 노출되면 사고)
 * - 운영 기능(health·지표)과 같은 자리에 모인다. 노출 여부도 management.endpoints.web.exposure 한 줄로 끈다
 * 대가: 12주 차 chaos-control이 부르려면 8081을 클러스터 안에서 닿게 해야 한다 (파드 직접 호출 또는 내부 전용 Service)
 *
 * @WriteOperation의 파라미터는 JSON 본문의 같은 이름 필드에서 온다. 문자열 → enum 변환은 actuator가 해 준다 (없는 값이면 400)
 */
@Component
@Endpoint(id = "experiments")
class ExperimentsEndpoint(
    private val lockSwitch: LockStrategySwitch,
) {

    @ReadOperation
    fun read(): Map<String, Any> = mapOf("lockStrategy" to lockSwitch.strategy)

    @WriteOperation
    fun change(lockStrategy: LockStrategy): Map<String, Any> {
        val previous = lockSwitch.change(lockStrategy)
        // 실험 타임라인의 기준점: 언제 무엇에서 무엇으로 바꿨나 (13주 차 차트 마커)
        log.warn("실험 스위치 변경: lockStrategy {} → {}", previous, lockStrategy)
        return mapOf("lockStrategy" to lockStrategy, "previous" to previous)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExperimentsEndpoint::class.java)
    }
}
