package lab.bank.transfer.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxEventTest {

    private fun newEvent() = OutboxEvent.create(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        topic = "transfer-events",
        messageKey = "key",
        payload = "{}",
        now = Instant.parse("2026-11-21T00:00:00Z"),
    )

    @Test
    fun `새 이벤트는 미발행이고 버전이 없다`() {
        val event = newEvent()

        assertFalse(event.isPublished)
        assertNull(event.publishedAt)
        assertNull(event.version)
    }

    @Test
    fun `발행 표시하면 발행 시각이 남는다`() {
        val event = newEvent()
        val now = Instant.parse("2026-11-21T00:00:05Z")

        event.markPublished(now)

        assertTrue(event.isPublished)
        assertEquals(now, event.publishedAt)
    }

    @Test
    fun `이미 발행된 이벤트를 또 표시하면 예외 - 한 행은 한 릴레이만 잡아야 한다`() {
        val event = newEvent()
        event.markPublished(Instant.now())

        assertThrows<IllegalStateException> { event.markPublished(Instant.now()) }
    }
}
