package lab.bank.bankmock.application

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 외부 은행의 행동을 설정값으로 바꾼다 (CLAUDE.md: 실험 스위치는 하드코딩 금지, 12주 차 패널 버튼과 연결).
 *
 * @property rejectAccountPrefix 받는 계좌 번호가 이걸로 시작하면 "계좌 없음"으로 거절. 테스트·시연에서 거절 경로를 결정적으로 재현
 * @property rejectRatePercent 그 밖의 요청을 이 확률(0~100)로 거절. k6로 "외부 거절이 섞인 부하"를 만들 때
 */
@ConfigurationProperties("bank-mock")
data class BankMockProperties(
    val rejectAccountPrefix: String = "999",
    val rejectRatePercent: Int = 0,
) {
    init {
        require(rejectRatePercent in 0..100) { "bank-mock.reject-rate-percent는 0~100: $rejectRatePercent" }
    }
}
