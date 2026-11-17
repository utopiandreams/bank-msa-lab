package lab.bank.notification

import lab.bank.common.event.TransferFinishedEvent
import lab.bank.notification.domain.NotificationStatus
import lab.bank.notification.domain.NotificationType
import lab.bank.notification.infrastructure.persistence.NotificationRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * notification 통합 테스트: 진짜 Kafka에 이벤트를 넣고, 진짜 DB에 알림이 생기는지 본다.
 * 리스너는 비동기라 결과가 나타날 때까지 기다린다 (awaitUntil).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, NotificationIntegrationTest.SenderConfig::class)
class NotificationIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class SenderConfig {
        @Bean
        @Primary
        fun recordingSender() = RecordingNotificationSender()
    }

    @Autowired lateinit var kafkaTemplate: KafkaTemplate<String, String>
    @Autowired lateinit var repository: NotificationRepository
    @Autowired lateinit var sender: RecordingNotificationSender
    @Autowired lateinit var jsonMapper: JsonMapper

    private fun event(status: String = TransferFinishedEvent.COMPLETED, failureCode: String? = null, amount: Long = 10_000) =
        TransferFinishedEvent(
            eventId = UUID.randomUUID(),
            schemaVersion = TransferFinishedEvent.SCHEMA_VERSION,
            occurredAt = Instant.now(),
            transferId = UUID.randomUUID(),
            requesterId = "chulsoo",
            fromAccountId = UUID.randomUUID(),
            toAccountId = UUID.randomUUID(),
            amount = amount,
            status = status,
            failureCode = failureCode,
        )

    private fun publish(key: String, json: String) {
        kafkaTemplate.send(TransferFinishedEvent.TOPIC, key, json).get()
    }

    private fun publish(event: TransferFinishedEvent) = publish(event.transferId.toString(), jsonMapper.writeValueAsString(event))

    private fun <T> awaitUntil(timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(200)
        }
        error("${timeoutMs}ms 안에 조건이 만족되지 않음")
    }

    private fun sentNotificationOf(event: TransferFinishedEvent) =
        awaitUntil { repository.findByEventId(event.eventId)?.takeIf { it.status == NotificationStatus.SENT } }

    @Test
    fun `완료 이벤트 - 알림을 기록하고 보낸다`() {
        val event = event(amount = 10_000)

        publish(event)

        val notification = sentNotificationOf(event)
        assertEquals(NotificationType.TRANSFER_COMPLETED, notification.type)
        assertEquals("chulsoo", notification.recipientId)
        assertEquals("10,000원 송금이 완료되었습니다.", notification.message)
        assertNotNull(notification.sentAt)
    }

    @Test
    fun `실패 이벤트 - 내부 코드 대신 고객용 문구로 알린다`() {
        val event = event(status = TransferFinishedEvent.FAILED, failureCode = "INSUFFICIENT_BALANCE", amount = 30_000)

        publish(event)

        val notification = sentNotificationOf(event)
        assertEquals(NotificationType.TRANSFER_FAILED, notification.type)
        assertEquals("30,000원 송금에 실패했습니다. (잔액 부족)", notification.message)
    }

    // 재현: 오프셋 커밋 전에 죽으면 같은 메시지를 다시 받는다 (at-least-once). 그걸 "같은 이벤트를 두 번 넣기"로 흉내 낸다
    @Test
    fun `같은 이벤트가 두 번 와도 알림은 하나, 발송도 한 번`() {
        val event = event()
        val marker = event()   // 뒤따르는 다른 이벤트: 이게 처리됐으면 앞의 중복도 이미 처리된 것 (같은 키 = 같은 파티션 = 순서 보장)

        publish(event)
        publish(event)
        publish(event.transferId.toString(), jsonMapper.writeValueAsString(marker))   // 일부러 같은 키로
        sentNotificationOf(marker)

        assertEquals(1, sender.sent.count { it.eventId == event.eventId })
        assertNotNull(repository.findByEventId(event.eventId))
    }

    @Test
    fun `모르는 필드가 추가된 이벤트도 읽는다 - 생산자가 먼저 필드를 추가해도 안 깨진다`() {
        val event = event()
        val withExtraField = jsonMapper.writeValueAsString(event).replaceFirst("{", """{"newFieldFromFuture":"x",""")

        publish(event.transferId.toString(), withExtraField)

        sentNotificationOf(event)
    }

    // 재현: 깨진 메시지에 예외를 던지면 재시도하느라 그 파티션 전체가 멈춘다 (poison pill)
    @Test
    fun `깨진 메시지는 건너뛰고 뒤의 정상 메시지는 처리한다`() {
        val next = event()
        val sameKey = next.transferId.toString()   // 같은 키 = 같은 파티션: 깨진 메시지 바로 뒤에 놓인다

        publish(sameKey, "{ 이건 JSON이 아님")
        publish(next)

        sentNotificationOf(next)
        assertTrue(sender.sent.any { it.eventId == next.eventId })
    }
}
