package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.TestcontainersConfiguration
import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.TransferStatus
import lab.bank.transfer.domain.UserId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 해소 대상 조회 (ADR-0005 결정 1·5): 무엇을 잡고, 다른 일꾼이 잡은 건 건너뛰나.
 *
 * DB는 테스트끼리 공유한다 → 다른 테스트가 남긴 오래된 행이 섞일 수 있다. 그래서 결과 전체가 아니라
 * "이 테스트가 만든 행 중 무엇이 잡혔나"를 본다. SKIP LOCKED 테스트만은 가장 오래된 행이어야 해서 1990년에 보낸 것으로 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class TransferUnresolvedQueryTest {

    @Autowired lateinit var transferRepository: TransferRepository
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    // 끝 상태는 DB CHECK 제약상 원장 ID(COMPLETED)·실패 코드(FAILED)가 있어야 한다
    private fun sent(at: Instant, status: TransferStatus): Transfer =
        transferRepository.save(
            Transfer(
                UUID.randomUUID(), UserId("chulsoo"), IdempotencyKey("q-${UUID.randomUUID()}"),
                UUID.randomUUID(), UUID.randomUUID(), Amount(1_000), status,
                if (status == TransferStatus.FAILED) "TEST" else null,
                if (status == TransferStatus.COMPLETED) UUID.randomUUID() else null,
                at, at, null,
            ),
        )

    private fun find(sentBefore: Instant, limit: Int = 10): List<Transfer> =
        transactionTemplate.execute { transferRepository.findUnresolvedForUpdate(sentBefore, limit) }!!

    @Test
    fun `PROCESSING·UNKNOWN 중 기준 시각 전에 보낸 것만, 오래된 순으로`() {
        val day = Instant.parse("2001-01-01T00:00:00Z")
        val unknown = sent(day.plusSeconds(2), TransferStatus.UNKNOWN)
        val processing = sent(day.plusSeconds(1), TransferStatus.PROCESSING)
        val completed = sent(day.plusSeconds(3), TransferStatus.COMPLETED)   // 끝 상태: 대상 아님
        val failed = sent(day.plusSeconds(4), TransferStatus.FAILED)
        val young = sent(day.plusSeconds(20), TransferStatus.UNKNOWN)       // 기준 시각 뒤: 아직 core가 처리 중일 수 있음
        val mine = setOf(unknown.id, processing.id, completed.id, failed.id, young.id)

        val found = find(sentBefore = day.plusSeconds(10), limit = 100).map { it.id }.filter { it in mine }

        assertEquals(listOf(processing.id, unknown.id), found)
    }

    @Test
    fun `limit만큼만 잡는다`() {
        val day = Instant.parse("2001-02-01T00:00:00Z")
        repeat(3) { sent(day.plusSeconds(it.toLong()), TransferStatus.UNKNOWN) }

        assertEquals(2, find(sentBefore = day.plusSeconds(10), limit = 2).size)
    }

    @Test
    fun `다른 트랜잭션이 잡고 있는 행은 기다리지 않고 건너뛴다 (SKIP LOCKED)`() {
        val day = Instant.parse("1990-01-01T00:00:00Z")   // 가장 오래된 행이어야 일꾼 A의 limit 1이 first를 잡는다
        val first = sent(day.plusSeconds(1), TransferStatus.UNKNOWN)
        val second = sent(day.plusSeconds(2), TransferStatus.UNKNOWN)
        val cutoff = day.plusSeconds(10)

        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        // 일꾼 A: 1건만 잡고(가장 오래된 first) 커밋하지 않은 채 붙잡고 있다
        val workerA = pool.submit<List<UUID>> {
            transactionTemplate.execute {
                val mine = transferRepository.findUnresolvedForUpdate(cutoff, 1).map { it.id }
                locked.countDown()
                release.await(10, TimeUnit.SECONDS)
                mine
            }
        }
        try {
            assertTrue(locked.await(10, TimeUnit.SECONDS))

            // 일꾼 B: A가 잡은 first는 건너뛰고 second만. 기다리지 않는다 (SKIP LOCKED가 없으면 A가 커밋할 때까지 막힘)
            val startedAt = System.nanoTime()
            val workerB = find(sentBefore = cutoff)
            val waitedMs = (System.nanoTime() - startedAt) / 1_000_000

            val bIds = workerB.map { it.id }
            assertTrue(second.id in bIds && first.id !in bIds, "B는 second만 잡아야 한다: $bIds")
            assertTrue(waitedMs < 2_000, "잠긴 행을 기다렸다: ${waitedMs}ms")
        } finally {
            release.countDown()
            assertEquals(listOf(first.id), workerA.get(10, TimeUnit.SECONDS))
            pool.shutdown()
        }
    }
}
