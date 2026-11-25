package lab.bank.bankmock.infrastructure.persistence

import lab.bank.bankmock.TestcontainersConfiguration
import lab.bank.bankmock.application.InterbankTransferService
import lab.bank.bankmock.application.SettlementFile
import lab.bank.bankmock.domain.InterbankOutcome
import lab.bank.bankmock.domain.InterbankTransfer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** 진짜 Postgres로: 멱등(같은 키 동시 요청), 정산 파일의 날짜 경계(KST), 기록이 프로세스 밖에 남는지 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class JdbcInterbankTransferStoreTest {

    @Autowired lateinit var store: JdbcInterbankTransferStore
    @Autowired lateinit var service: InterbankTransferService
    @Autowired lateinit var jdbc: JdbcClient

    private fun record(key: String, at: Instant, amount: Long = 10_000) =
        InterbankTransfer(key, "088", "1234567890", amount, InterbankOutcome.ACCEPTED, null, at)

    @Test
    fun `같은 키가 동시에 16번 와도 행은 하나, 모두 같은 판정을 받는다`() {
        val key = UUID.randomUUID().toString()
        val threads = 16
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val futures = (1..threads).map {
            pool.submit<InterbankTransfer> { start.await(); service.transfer(key, "088", "1234567890", 10_000) }
        }
        start.countDown()
        val results = futures.map { it.get(10, TimeUnit.SECONDS) }.toSet()
        pool.shutdown()

        assertEquals(1, results.size, "같은 키인데 답이 갈렸다: $results")
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM bankmock.interbank_transfer WHERE idempotency_key = :k").param("k", key).query(Long::class.java).single())
    }

    @Test
    fun `먼저 저장된 기록이 이긴다 - 나중 후보는 버려진다`() {
        val key = UUID.randomUUID().toString()
        val first = store.saveIfAbsent(record(key, Instant.parse("2002-01-01T00:00:00Z")))
        val second = store.saveIfAbsent(record(key, Instant.parse("2002-01-01T00:00:05Z")))
        assertEquals(first, second)
        assertEquals(first, store.find(key))
    }

    @Test
    fun `정산 파일은 KST 날짜로 묶는다 - 자정 직전·직후가 갈린다`() {
        val day = LocalDate.parse("2002-02-10")
        val kstMidnight = Instant.parse("2002-02-09T15:00:00Z")   // 2002-02-10 00:00 KST
        val before = UUID.randomUUID().toString().also { store.saveIfAbsent(record(it, kstMidnight.minusMillis(1))) }
        val first = UUID.randomUUID().toString().also { store.saveIfAbsent(record(it, kstMidnight)) }
        val last = UUID.randomUUID().toString().also { store.saveIfAbsent(record(it, kstMidnight.plusSeconds(86_399))) }
        val next = UUID.randomUUID().toString().also { store.saveIfAbsent(record(it, kstMidnight.plusSeconds(86_400))) }

        val keys = service.processedOn(day).map { it.idempotencyKey }

        assertEquals(listOf(first, last), keys)
        val file = SettlementFile.write(day, service.processedOn(day))
        assertEquals("T|2|20000", file.trimEnd().lines().last())
        assertEquals(listOf(before), service.processedOn(day.minusDays(1)).map { it.idempotencyKey }.filter { it == before })
        assertEquals(listOf(next), service.processedOn(day.plusDays(1)).map { it.idempotencyKey }.filter { it == next })
    }
}
