package lab.bank.transfer.domain

// 송금 상태 머신 (ADR-0001). 전이 규칙은 Transfer의 도메인 메서드가 갖는다
//
//   REQUESTED ──▶ PROCESSING ──core 성공──▶ COMPLETED
//                           ├─core 거절──▶ FAILED
//                           └─결과 모름──▶ UNKNOWN ──(8주 차: 상태 조회로)──▶ COMPLETED / FAILED
enum class TransferStatus {
    REQUESTED,    // 요청됨: 송금 기록 생성, 송금 ID 발급
    PROCESSING,   // 처리중: core 호출 직전에 커밋. core 호출 후 결과 반영 전에 죽으면 여기 남는다
    COMPLETED,    // 완료 (끝 상태)
    FAILED,       // 실패: core가 거절 (잔액 부족, 계좌 없음 등) (끝 상태)
    UNKNOWN,      // 확인필요: 타임아웃 등으로 core가 처리했는지 모름
}
