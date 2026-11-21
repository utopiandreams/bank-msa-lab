package lab.bank.transfer.infrastructure.kafka

import lab.bank.transfer.domain.OutboxEvent
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * 릴레이의 전송 부품 테스트: Spring 컨텍스트 없이, 진짜 Kafka(Testcontainers)에 붙인다.
 * 프로듀서 설정은 application.yaml과 같은 값으로 만든다 (acks=all, 짧은 max.block.ms).
 */
class KafkaOutboxSenderTest {

    private fun template(bootstrap: String) = KafkaTemplate(
        DefaultKafkaProducerFactory<String, String>(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG to 1000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 3000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 5000,
            ),
        ),
    )

    private fun outboxEvent(key: String = UUID.randomUUID().toString()) = OutboxEvent.create(
        eventId = UUID.randomUUID(),
        aggregateId = UUID.randomUUID(),
        topic = TOPIC,
        messageKey = key,
        payload = """{"hello":"$key"}""",
        now = Instant.now(),
    )

    @Test
    fun `outbox에 적힌 topic·key·payload를 그대로 보낸다 - 내용은 모른다`() {
        val event = outboxEvent()

        KafkaOutboxSender(template(kafka.bootstrapServers)).send(event, Duration.ofSeconds(10))

        val record = consumeOne(event.messageKey)
        assertEquals(event.messageKey, record.key())
        assertEquals(event.payload, record.value())
    }

    // 이전 발행기와 반대: 실패를 삼키지 않는다. 삼키면 릴레이가 안 보낸 걸 "보냄"으로 표시한다
    @Test
    fun `Kafka에 못 붙으면 예외를 던진다 - 그것도 금방 (max_block_ms)`() {
        val sender = KafkaOutboxSender(template("localhost:1"))   // 아무도 없는 포트

        val elapsed = measureTime {
            assertFails { sender.send(outboxEvent(), Duration.ofSeconds(10)) }
        }

        assertTrue(elapsed.inWholeMilliseconds < 3_000, "max.block.ms(1초) 근처에서 실패해야 한다: $elapsed")
    }

    // 토픽을 테스트끼리 같이 쓰고 earliest로 읽는다 → 키로 골라 읽는다
    private fun consumeOne(key: String): ConsumerRecord<String, String> =
        KafkaConsumer<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "test-${UUID.randomUUID()}",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        ).use { consumer ->
            consumer.subscribe(listOf(TOPIC))
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == key }?.let { return it }
            }
            error("15초 안에 메시지를 못 받음")
        }

    companion object {
        private const val TOPIC = "outbox-sender-test"

        // 테스트용 Kafka는 토픽 자동 생성이 켜져 있다 (클러스터는 auto.create 끔, 토픽은 KafkaTopic 선언으로)
        private val kafka = KafkaContainer("apache/kafka:4.3.2").apply { start() }

        @JvmStatic
        @AfterAll
        fun stop() = kafka.stop()
    }
}
