package lab.bank.transfer.infrastructure.outbox

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.domain.OutboxEvent
import lab.bank.transfer.infrastructure.persistence.OutboxEventMapper
import lab.bank.transfer.infrastructure.persistence.OutboxJpaRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["outbox.relay.enabled=false"])
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class OutboxCleanupTest {

    @Autowired lateinit var cleanup: OutboxCleanup
    @Autowired lateinit var repository: OutboxJpaRepository

    private fun saveEvent(createdAt: Instant, publishedAt: Instant?): UUID {
        val event = OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "t", "k", "{}", createdAt)
        if (publishedAt != null) event.markPublished(publishedAt)
        return repository.save(OutboxEventMapper.toEntity(event)).id
    }

    @Test
    fun `발행한 지 보관 기간이 지난 행만 지운다 - 미발행은 아무리 오래돼도 남긴다`() {
        val now = Instant.now()
        val oldPublished = saveEvent(now.minus(Duration.ofDays(10)), publishedAt = now.minus(Duration.ofDays(8)))
        val recentPublished = saveEvent(now.minus(Duration.ofDays(2)), publishedAt = now.minus(Duration.ofDays(1)))
        val oldUnpublished = saveEvent(now.minus(Duration.ofDays(30)), publishedAt = null)

        cleanup.cleanup()

        assertFalse(repository.existsById(oldPublished))
        assertTrue(repository.existsById(recentPublished))
        assertTrue(repository.existsById(oldUnpublished), "오래 안 나간 이벤트는 지울 대상이 아니라 들여다볼 대상")
    }

    @Test
    fun `두 번 돌아도 결과가 같다 - 파드마다 돌아도 락이 필요 없다`() {
        val now = Instant.now()
        val recentPublished = saveEvent(now, publishedAt = now)

        cleanup.cleanup()
        cleanup.cleanup()

        assertTrue(repository.existsById(recentPublished))
    }
}
