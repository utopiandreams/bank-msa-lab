package lab.bank.transfer.infrastructure.outbox

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Outbox 릴레이·정리 설정 (application.yaml의 outbox.*). 하드코딩하지 않는 이유: 실험 스위치 (CLAUDE.md 5장)
 * 예) 12주 차 패널 "릴레이 멈추기" → 미발행이 쌓였다가 다시 켜면 한꺼번에 나가는 장면
 */
@ConfigurationProperties(prefix = "outbox")
data class OutboxProperties(
    val relay: Relay = Relay(),
    val cleanup: Cleanup = Cleanup(),
) {
    /**
     * @property enabled      false면 릴레이 빈을 만들지 않는다 (이벤트는 outbox에 쌓이기만 함)
     * @property pollInterval 한 회차가 끝나고 다음 회차까지 쉬는 시간 (fixedDelay). 이벤트 지연의 상한 ≈ 이 값 + 전송 시간
     * @property batchSize    한 회차에 잡는 최대 행 수. 크면 처리량↑, 대신 한 트랜잭션이 DB 커넥션·행 잠금을 오래 쥔다
     * @property sendTimeout  한 건의 ack를 기다리는 최대 시간 (프로듀서 delivery.timeout.ms 5초보다 길게: 프로듀서가 먼저 결론 내게)
     */
    data class Relay(
        val enabled: Boolean = true,
        val pollInterval: Duration = Duration.ofSeconds(1),
        val batchSize: Int = 100,
        val sendTimeout: Duration = Duration.ofSeconds(10),
    )

    /**
     * @property retention 발행한 지 이만큼 지난 행을 지운다. 그 전까지는 "이 이벤트 나갔나?"를 DB로 확인하고 필요하면 다시 보낼 수 있다
     * @property cron      정리 시각 (Asia/Seoul). 트래픽이 적은 새벽
     */
    data class Cleanup(
        val retention: Duration = Duration.ofDays(7),
        val cron: String = "0 0 4 * * *",
    )
}
