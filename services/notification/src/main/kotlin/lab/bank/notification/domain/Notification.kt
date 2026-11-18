package lab.bank.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import lab.bank.common.event.TransferFinishedEvent
import java.time.Instant
import java.util.UUID

/**
 * 알림 (JPA 엔티티 = 도메인). notification은 외부 의존이 적은 단순 서비스라 core처럼 엔티티에 규칙을 둔다
 * (transfer처럼 헥사고날로 나눌 만큼 포트가 많지 않다. 15주 차에 외부 발송 업체가 붙으면 다시 본다).
 */
@Entity
@Table(name = "notification", schema = "notification")
class Notification(
    @Id
    val id: UUID,

    // UNIQUE (uq_notification_event_id): 같은 이벤트 = 같은 알림
    @Column(nullable = false, updatable = false)
    val eventId: UUID,

    @Column(nullable = false, updatable = false)
    val transferId: UUID,

    @Column(nullable = false, updatable = false, length = 64)
    val recipientId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    val type: NotificationType,

    @Column(nullable = false, updatable = false, length = 500)
    val message: String,

    @Column(nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: NotificationStatus = NotificationStatus.PENDING
        protected set

    var sentAt: Instant? = null
        protected set

    // null이면 Spring Data가 새 엔티티로 보고 INSERT (ID를 앱이 만들기 때문, core와 같은 이유)
    @Version
    var version: Long? = null
        protected set

    fun markSent() {
        check(status == NotificationStatus.PENDING) { "보낼 수 없는 상태: $status" }
        status = NotificationStatus.SENT
        sentAt = Instant.now()
    }

    companion object {
        fun from(event: TransferFinishedEvent): Notification {
            val type = when (event.status) {
                TransferFinishedEvent.COMPLETED -> NotificationType.TRANSFER_COMPLETED
                TransferFinishedEvent.FAILED -> NotificationType.TRANSFER_FAILED
                // 생산자(transfer)보다 먼저 배포해야 하는 줄: 모르는 상태는 아래 else에서 예외 → 깨진 메시지로 건너뛰어
                // 고객이 "돌려드렸습니다" 알림을 영영 못 받는다 (pitfalls 18: 소비자 먼저)
                TransferFinishedEvent.CANCELLED -> NotificationType.TRANSFER_CANCELLED
                else -> throw IllegalArgumentException("알 수 없는 송금 상태: ${event.status}")
            }
            return Notification(
                id = UUID.randomUUID(),
                eventId = event.eventId,
                transferId = event.transferId,
                recipientId = event.requesterId,
                type = type,
                message = message(type, event),
                createdAt = Instant.now(),
            )
        }

        private fun message(type: NotificationType, event: TransferFinishedEvent): String {
            val amount = "%,d원".format(event.amount)
            return when (type) {
                NotificationType.TRANSFER_COMPLETED -> "${amount} 송금이 완료되었습니다."
                NotificationType.TRANSFER_FAILED -> "${amount} 송금에 실패했습니다. (${reason(event.failureCode)})"
                NotificationType.TRANSFER_CANCELLED -> "${amount} 타행 송금이 처리되지 않아 출금한 금액을 돌려드렸습니다. (${reason(event.failureCode)})"
            }
        }

        // 고객에게 보이는 문구. 내부 코드(INSUFFICIENT_BALANCE)를 그대로 보여 주지 않는다
        private fun reason(failureCode: String?): String = when (failureCode) {
            "INSUFFICIENT_BALANCE" -> "잔액 부족"
            "ACCOUNT_NOT_FOUND" -> "계좌를 확인해 주세요"
            else -> "고객센터에 문의해 주세요"
        }
    }
}
