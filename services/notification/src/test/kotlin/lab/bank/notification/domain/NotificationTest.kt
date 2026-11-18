package lab.bank.notification.domain

import lab.bank.common.event.TransferFinishedEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 송금 상태 → 알림 종류·문구. CANCELLED는 생산자(transfer)보다 먼저 배포돼야 하는 매핑 (pitfalls 18)
class NotificationTest {

    private fun event(status: String, failureCode: String? = null) = TransferFinishedEvent(
        eventId = UUID.randomUUID(), schemaVersion = TransferFinishedEvent.SCHEMA_VERSION, occurredAt = Instant.now(),
        transferId = UUID.randomUUID(), requesterId = "chulsoo", fromAccountId = UUID.randomUUID(),
        toAccountId = null, amount = 100_000, status = status, failureCode = failureCode,
        toBankCode = "088", toAccountNumber = "9990001",
    )

    @Test
    fun `타행 이체 취소(보상 완료)는 돌려드렸다는 알림`() {
        val n = Notification.from(event(TransferFinishedEvent.CANCELLED, "ACCOUNT_NOT_FOUND"))
        assertEquals(NotificationType.TRANSFER_CANCELLED, n.type)
        assertTrue(n.message.contains("돌려드렸습니다"), n.message)
        assertTrue(n.message.contains("100,000원"), n.message)
    }

    @Test
    fun `받는 계좌 ID가 없는 타행 이벤트도 완료 알림을 만든다 - 계약 넓힘`() {
        assertEquals(NotificationType.TRANSFER_COMPLETED, Notification.from(event(TransferFinishedEvent.COMPLETED)).type)
    }

    @Test
    fun `모르는 상태는 예외 - 리스너가 깨진 메시지로 건너뛴다`() {
        assertThrows<IllegalArgumentException> { Notification.from(event("SOMETHING_NEW")) }
    }
}
