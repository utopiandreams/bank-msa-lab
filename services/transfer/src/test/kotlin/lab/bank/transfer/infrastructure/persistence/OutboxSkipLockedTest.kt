package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.domain.OutboxEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 릴레이 여러 개(transfer replicas ≥ 2)가 동시에 돌 때 같은 행을 잡지 않는가: FOR UPDATE SKIP LOCKED.
 *
 * 릴레이 A가 트랜잭션 안에서 5건을 잡고 **잠금을 쥔 채 기다리는 동안**, 릴레이 B가 10건을 달라고 한다.
 * - SKIP LOCKED: B는 기다리지 않고 A가 잡은 5건을 건너뛰어 나머지 5건을 받는다 (겹침 0)
 * - FOR UPDATE만: B는 A가 커밋할 때까지 막힌다 / 잠금 없음: B도 같은 5건을 받아 두 번 보낸다
 */
@SpringBootTest(properties = ["outbox.relay.enabled=false"])
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class OutboxSkipLockedTest {

    @Autowired lateinit var repository: OutboxJpaRepository
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    @BeforeEach
    fun setUp() {
        repository.deleteAll()
        val base = Instant.now()
        repeat(10) { i ->
            repository.save(OutboxEventMapper.toEntity(
                OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "t", "k$i", "{}", base.plusMillis(i.toLong()))
            ))
        }
    }

    @Test
    fun `A가 잠금을 쥔 동안 B는 기다리지 않고 나머지 행만 가져간다 - 겹침 없음`() {
        val aLocked = CountDownLatch(1)
        val bDone = CountDownLatch(1)

        // 릴레이 A: 5건 잡고, B가 끝날 때까지 트랜잭션(=잠금)을 놓지 않는다 (Kafka로 보내는 중인 상황)
        val a = CompletableFuture.supplyAsync {
            transactionTemplate.execute {
                val rows = repository.findUnpublished(5).map { it.id }
                aLocked.countDown()
                bDone.await(10, TimeUnit.SECONDS)
                rows
            }!!
        }
        aLocked.await(10, TimeUnit.SECONDS)

        // 릴레이 B: 10건 달라고 한다. 막히면 아래 get(3초)에서 타임아웃으로 실패
        val b = CompletableFuture.supplyAsync {
            transactionTemplate.execute { repository.findUnpublished(10).map { it.id } }!!
                .also { bDone.countDown() }
        }

        val bRows = b.get(3, TimeUnit.SECONDS)
        val aRows = a.get(10, TimeUnit.SECONDS)

        assertEquals(5, aRows.size)
        assertEquals(5, bRows.size, "B는 A가 잡은 5건을 건너뛰고 나머지 5건만")
        assertTrue(aRows.intersect(bRows.toSet()).isEmpty(), "같은 행을 두 릴레이가 잡으면 두 번 발행된다")
    }
}
