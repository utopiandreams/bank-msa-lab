package lab.bank.notification.domain

enum class NotificationType {
    TRANSFER_COMPLETED,
    TRANSFER_FAILED,
    TRANSFER_CANCELLED,   // 타행 이체 출금 후 외부 거절 → 돌려줌 (ADR-0004)
}
