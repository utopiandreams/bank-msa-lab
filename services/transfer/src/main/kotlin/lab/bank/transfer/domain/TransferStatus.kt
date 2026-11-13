package lab.bank.transfer.domain

// 송금 상태 머신 (ADR-0001). 허용 전이는 canTransitTo, 전이 실행은 Transfer의 도메인 메서드
//
//   REQUESTED ──▶ PROCESSING ──core 성공──▶ COMPLETED
//                           ├─core 거절──▶ FAILED
//                           └─결과 모름──▶ UNKNOWN ──재전송(고객 재요청) / 상태 조회(8주 차)──▶ COMPLETED / FAILED
//                                            ↺ 재전송이 또 모름 (UNKNOWN → UNKNOWN: updatedAt = 마지막 시도 시각)
enum class TransferStatus {
    REQUESTED,    // 요청됨: 송금 기록 생성, 송금 ID 발급
    PROCESSING,   // 처리중: core 호출 직전에 커밋. core 호출 후 결과 반영 전에 죽으면 여기 남는다
    COMPLETED,    // 완료 (끝 상태)
    FAILED,       // 실패: core가 거절 (잔액 부족, 계좌 없음 등) (끝 상태)
    UNKNOWN,      // 확인필요: 타임아웃 등으로 core가 처리했는지 모름
    ;

    fun canTransitTo(to: TransferStatus): Boolean =
        when(this) {
            REQUESTED -> when (to) {
                PROCESSING -> true
                else -> false
            }
            PROCESSING -> when (to) {
                COMPLETED, FAILED, UNKNOWN -> true
                else -> false
            }
            UNKNOWN -> when (to) {
                COMPLETED, FAILED, UNKNOWN -> true
                else -> false
            }
            COMPLETED, FAILED -> false
        }
}
