package lab.bank.transfer.infrastructure.kafka

import lab.bank.common.event.TransferFinishedEvent
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.BankCode
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.UserId
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
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * 발행 어댑터 테스트: Spring 컨텍스트 없이, 진짜 Kafka(Testcontainers)에 붙인다.
 * 프로듀서 설정은 application.yaml과 같은 값으로 만든다 (acks=all, 짧은 max.block.ms).
 */
class KafkaTransferEventPublisherTest {

    private val jsonMapper = JsonMapper.builder().build()

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

    private fun finishedTransfer(): Transfer =
        Transfer.request(UserId("chulsoo"), IdempotencyKey("k-${UUID.randomUUID()}"), UUID.randomUUID(), UUID.randomUUID(), Amount(10_000))
            .apply {
                startProcessing()
                failedProcessing("INSUFFICIENT_BALANCE")
            }

    @Test
    fun `토픽 transfer-events에 키 = 송금 ID, 값 = 이벤트 JSON으로 보낸다`() {
        val transfer = finishedTransfer()
        val publisher = KafkaTransferEventPublisher(template(kafka.bootstrapServers), jsonMapper)

        publisher.publishFinished(transfer)

        val record = consumeOne(transfer.id.toString())
        assertEquals(transfer.id.toString(), record.key())
        val json = jsonMapper.readTree(record.value())
        assertEquals(transfer.id.toString(), json["transferId"].asString())
        assertEquals("chulsoo", json["requesterId"].asString())
        assertEquals(10_000, json["amount"].asLong())
        assertEquals("FAILED", json["status"].asString())
        assertEquals("INSUFFICIENT_BALANCE", json["failureCode"].asString())
        assertEquals(TransferFinishedEvent.SCHEMA_VERSION, json["schemaVersion"].asInt())
        assertTrue(json["eventId"].asString().isNotBlank())
    }

    @Test
    fun `타행 CANCELLED - 받는 쪽은 은행 코드·계좌번호, failureCode는 외부 거절 사유`() {
        val transfer = InterbankTransfer.request(UserId("chulsoo"), IdempotencyKey("k-${UUID.randomUUID()}"), UUID.randomUUID(), BankCode("004"), "123-456", Amount(10_000))
            .apply {
                withdrawRequested()
                withdrawn(UUID.randomUUID())
                externalBankRequested()
                externalBankRejected("ACCOUNT_NOT_FOUND")
                cancelled(UUID.randomUUID())
            }
        val publisher = KafkaTransferEventPublisher(template(kafka.bootstrapServers), jsonMapper)

        publisher.publishFinished(transfer)

        val json = jsonMapper.readTree(consumeOne(transfer.id.toString()).value())
        assertEquals("CANCELLED", json["status"].asString())
        assertEquals("ACCOUNT_NOT_FOUND", json["failureCode"].asString())
        assertEquals("004", json["toBankCode"].asString())
        assertEquals("123-456", json["toAccountNumber"].asString())
        assertTrue(json["toAccountId"].isNull)
    }

    // 재현: max.block.ms 기본값(60초)이면 Kafka가 죽었을 때 송금 API가 건마다 60초 멈춘다 (pitfalls 20)
    @Test
    fun `Kafka에 못 붙어도 예외를 던지지 않고 금방 돌아온다 - 송금 응답을 붙잡지 않는다`() {
        val publisher = KafkaTransferEventPublisher(template("localhost:1"), jsonMapper)   // 아무도 없는 포트

        val elapsed = measureTime { publisher.publishFinished(finishedTransfer()) }

        assertTrue(elapsed.inWholeMilliseconds < 3_000, "max.block.ms(1초) 근처에서 돌아와야 한다: $elapsed")
    }

    // 토픽을 테스트끼리 같이 쓰고 earliest로 읽는다 → 앞 테스트의 메시지가 먼저 보이므로 키로 골라 읽는다
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
            consumer.subscribe(listOf(TransferFinishedEvent.TOPIC))
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == key }?.let { return it }
            }
            error("15초 안에 메시지를 못 받음")
        }

    companion object {
        // 테스트용 Kafka는 토픽 자동 생성이 켜져 있다 (클러스터는 auto.create 끔, 토픽은 KafkaTopic 선언으로)
        private val kafka = KafkaContainer("apache/kafka:4.3.2").apply { start() }

        @JvmStatic
        @AfterAll
        fun stop() = kafka.stop()
    }
}
