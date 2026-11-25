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

    // 끝 상태: 더 이상 바뀌지 않는다. 여기로 바뀌는 순간 송금 이벤트를 발행한다 (ADR-0003)
    val isFinal: Boolean get() = this == COMPLETED || this == FAILED

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

enum class InterbankTransferStatus {
    REQUESTED,

    WITHDRAW_REQUESTED,
    WITHDRAWN,

    EXTERNAL_BANK_REQUESTED,
    // 출금 취소(보상)를 요청 중. 의도 상태는 행위로 짓는다 (pitfalls 39, 설계 연습 08 2부 결정 3, Flyway V8: 구 WITHDRAW_CANCEL_REQUESTED).
    // "왜" 취소하나(외부 거절 / 외부에 안 보냄 / 외부 기록 없음)는 상태가 아니라 사유 칸에
    WITHDRAW_CANCEL_REQUESTED,

    FAILED,
    CANCELLED,
    COMPLETED,
    ;

    fun isFinal(): Boolean = this == CANCELLED || this == COMPLETED || this == FAILED

    fun canTransitTo(to: InterbankTransferStatus): Boolean {
        return when(this) {
            REQUESTED -> WITHDRAW_REQUESTED == to
            WITHDRAW_REQUESTED -> when (to) {
                WITHDRAWN, FAILED, WITHDRAW_REQUESTED -> true
                else -> false
            }

            WITHDRAWN -> EXTERNAL_BANK_REQUESTED == to
            EXTERNAL_BANK_REQUESTED -> when (to) {
                COMPLETED, WITHDRAW_CANCEL_REQUESTED, EXTERNAL_BANK_REQUESTED -> true
                else -> false
            }

            WITHDRAW_CANCEL_REQUESTED -> when (to) {
                CANCELLED, WITHDRAW_CANCEL_REQUESTED -> true
                else -> false
            }

            FAILED, CANCELLED, COMPLETED -> false
        }
    }
}