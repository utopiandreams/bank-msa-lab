package lab.bank.bankmock.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 외부 은행의 행동을 설정값으로 바꾼다 (CLAUDE.md: 실험 스위치는 하드코딩 금지, 12주 차 패널 버튼과 연결).
 *
 * @property rejectAccountPrefix 받는 계좌 번호가 이걸로 시작하면 "계좌 없음"으로 거절. 테스트·시연에서 거절 경로를 결정적으로 재현
 * @property rejectRatePercent 그 밖의 요청을 이 확률(0~100)로 거절. k6로 "외부 거절이 섞인 부하"를 만들 때
 * @property processingDelay 받은 뒤 판정까지 걸리는 시간 (실험 스위치). [processingDeadline]을 넘으면 처리하지 않는다
 *           → 보내는 쪽에선 "안 보내진 것"과 구분이 안 되는 결과 불명. 조회하면 "없음"
 * @property responseDelay 판정·기록한 **뒤** 응답까지 걸리는 시간 (실험 스위치). 보내는 쪽 read-timeout(3초)보다 길게 주면
 *           "외부는 처리했는데 우리는 응답을 못 받음" = 결과 불명. 조회하면 "있음"
 * @property processingDeadline **처리 상한 (외부와의 계약, 8주 차 결정 1)**: 받은 뒤 이 시간 안에 판정하지 못하면 처리하지 않는다.
 *           보내는 쪽은 이 약속이 있어야 "보낸 지 상한이 지났는데 조회하니 없음"을 "처리 안 됨"으로 믿고 출금을 취소할 수 있다.
 *           transfer의 해소 기준 시간(transfer.resolution.external-check-after)은 이 값 + 여유여야 한다
 */
@ConfigurationProperties("bank-mock")
data class BankMockProperties(
    val rejectAccountPrefix: String = "999",
    val rejectRatePercent: Int = 0,
    val processingDelay: Duration = Duration.ZERO,
    val responseDelay: Duration = Duration.ZERO,
    val processingDeadline: Duration = Duration.ofSeconds(10),
) {
    init {
        require(rejectRatePercent in 0..100) { "bank-mock.reject-rate-percent는 0~100: $rejectRatePercent" }
        require(!processingDelay.isNegative && !responseDelay.isNegative) { "bank-mock 지연은 0 이상" }
        require(processingDeadline.isPositive) { "bank-mock.processing-deadline은 0보다 커야 함: $processingDeadline" }
    }
}
