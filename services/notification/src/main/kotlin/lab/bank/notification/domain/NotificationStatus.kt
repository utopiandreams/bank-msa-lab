package lab.bank.notification.domain

// 알림 상태. 진짜 문자·푸시는 외부 연동이라 transfer → core와 같은 "결과 불명"이 생긴다 (ADR-0003 결정 6, 개발자 지적)
// 지금은 가짜 발송기라 PENDING → SENT만 쓴다. FAILED·재시도·결과 불명은 15주 차(외부 장애)에
enum class NotificationStatus {
    PENDING,   // 기록됨, 아직 안 보냄 (보내기 직전에 죽으면 여기 남는다 → 15주 차 재발송 대상)
    SENT,
    FAILED,
}
