package lab.bank.transfer.application

import lab.bank.common.event.TransferFinishedEvent
import lab.bank.transfer.FakeCorePostingPort
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.TransferStatus
import lab.bank.transfer.domain.UserId
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 6주 차 재현: **커밋과 발행 사이의 장애로 이벤트가 유실된다.**
 *
 * 지금 구조: [트랜잭션] 송금 COMPLETED 커밋 → (트랜잭션 밖) kafkaTemplate.send()
 * 커밋은 됐는데 send가 실패하면 로그 한 줄만 남고 다시 보낼 근거가 어디에도 없다 → Kafka가 살아나도 영영 안 나간다.
 *
 * 장애 흉내: 프로듀서가 브로커 메타데이터를 받기 **전에** Kafka를 일시정지(docker pause) → send()가 max.block.ms(1초) 뒤 예외
 *   → 레코드가 프로듀서 밖으로 아예 나가지 않는다 (확실한 미발행).
 * 기대(Outbox 뒤): Kafka가 돌아오면 이벤트가 결국 나간다. **Outbox 전에는 이 테스트가 실패한다 = 재현.**
 *
 * 주의 (2026-11-21 첫 시도에서 발견): 연결된 뒤에 pause하면 재현이 안 된다. 프로듀서는 delivery.timeout.ms 뒤 "실패"를 보고하지만
 *   커널 소켓 버퍼에 들어간 요청을 Kafka가 깨어나며 처리해 **실제로는 기록된다**. 발행 타임아웃 ≠ 미발행 (pitfalls 29)
 *
 * 가짜 발행기(FakeCoreConfiguration) 대신 진짜 KafkaTransferEventPublisher를 쓴다. core만 가짜.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, TransferEventLossTest.FakeCoreOnly::class)
class TransferEventLossTest {

    @TestConfiguration(proxyBeanMethods = false)
    class FakeCoreOnly {
        @Bean
        @Primary
        fun fakeCorePostingPort() = FakeCorePostingPort()
    }

    @Autowired lateinit var transferUseCase: TransferUseCase

    private val requester = UserId("chulsoo")

    private fun transfer() = transferUseCase.transfer(
        requester, IdempotencyKey("key-${UUID.randomUUID()}"), UUID.randomUUID(), UUID.randomUUID(), Amount(10_000),
    ).transfer

    @Test
    fun `커밋 직후 Kafka가 멈춰도 Kafka가 돌아오면 송금 완료 이벤트는 결국 나간다`() {
        // 1. 장애: 프로듀서가 연결하기 전에 Kafka가 멈춤 → 송금
        pauseKafka()
        val lost = try {
            transfer().also {
                // Outbox 뒤: 장애를 몇 초 유지해 릴레이가 실제로 실패(회차 중단)하고, 복구 뒤 다음 회차에 보내는 경로까지 지나게 한다
                Thread.sleep(3_000)
            }
        } finally {
            unpauseKafka()
        }

        // 송금 자체는 끝났다 (고객 돈은 움직였다)
        assertEquals(TransferStatus.COMPLETED, lost.status)

        // 2. 대조군: Kafka가 돌아온 뒤의 송금은 이벤트가 나간다 (파이프라인은 정상)
        val control = transfer()
        assertNotNull(consume(control.id.toString(), Duration.ofSeconds(20)), "Kafka 복구 뒤 송금은 이벤트가 나가야 한다")

        // 3. 장애 중 송금: Kafka가 돌아왔으니 결국 나와야 한다. Outbox 전에는 여기서 실패 = 유실 재현
        assertNotNull(
            consume(lost.id.toString(), Duration.ofSeconds(20)),
            "송금 ${lost.id}는 COMPLETED로 커밋됐는데 이벤트가 끝내 나가지 않았다 (커밋과 발행 사이 유실)",
        )
    }

    private fun pauseKafka() {
        kafka.dockerClient.pauseContainerCmd(kafka.containerId).exec()
    }

    private fun unpauseKafka() {
        kafka.dockerClient.unpauseContainerCmd(kafka.containerId).exec()
    }

    // 토픽을 earliest로 처음부터 읽고 키(송금 ID)로 골라낸다
    private fun consume(key: String, timeout: Duration): ConsumerRecord<String, String>? =
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
            val deadline = System.currentTimeMillis() + timeout.toMillis()
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).firstOrNull { it.key() == key }?.let { return it }
            }
            null
        }

    companion object {
        private val kafka = KafkaContainer("apache/kafka:4.3.2").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }

        @JvmStatic
        @AfterAll
        fun stop() = kafka.stop()
    }
}
