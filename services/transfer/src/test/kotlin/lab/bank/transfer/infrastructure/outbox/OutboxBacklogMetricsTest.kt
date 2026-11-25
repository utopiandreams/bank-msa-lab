package lab.bank.transfer.infrastructure.outbox

import io.micrometer.core.instrument.MeterRegistry
import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.domain.OutboxEvent
import lab.bank.transfer.infrastructure.persistence.OutboxEventMapper
import lab.bank.transfer.infrastructure.persistence.OutboxJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = ["outbox.relay.enabled=false"])
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class OutboxBacklogMetricsTest {

    @Autowired lateinit var repository: OutboxJpaRepository
    @Autowired lateinit var registry: MeterRegistry

    @BeforeEach
    fun clean() = repository.deleteAll()

    private fun saveEvent(createdAt: Instant, publishedAt: Instant?) {
        val event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "t", "k", "{}", createdAt)
        if (publishedAt != null) event.markPublished(publishedAt)
        repository.save(OutboxEventMapper.toEntity(event))
    }

    @Test
    fun `미발행 행만 세고, 가장 오래된 미발행 행의 나이를 잰다`() {
        val now = Instant.now()
        saveEvent(now.minus(Duration.ofMinutes(10)), publishedAt = null)
        saveEvent(now.minus(Duration.ofMinutes(1)), publishedAt = null)
        saveEvent(now.minus(Duration.ofHours(1)), publishedAt = now)   // 발행된 행은 아무리 오래돼도 안 센다

        val row = repository.backlog()

        assertEquals(2, row.unpublished)
        assertTrue(row.oldestAgeSeconds in 590.0..700.0, "10분 전 행이 가장 오래됨: ${row.oldestAgeSeconds}")
    }

    @Test
    fun `미발행이 없으면 행 수 0, 나이 0 - 지표로 등록되어 있다`() {
        val row = repository.backlog()
        assertEquals(0, row.unpublished)
        assertEquals(0.0, row.oldestAgeSeconds)

        assertTrue(registry.find("transfer.outbox.unpublished").gauge() != null)
        assertTrue(registry.find("transfer.outbox.oldest.unpublished.age").gauge() != null)
    }
}
