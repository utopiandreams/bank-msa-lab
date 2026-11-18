package lab.bank.bankmock.application

import lab.bank.bankmock.domain.InterbankOutcome
import lab.bank.bankmock.domain.RejectReason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InterbankTransferServiceTest {

    private fun service(rejectRatePercent: Int = 0, random: Random = Random(42)) =
        InterbankTransferService(BankMockProperties(rejectRatePercent = rejectRatePercent), random)

    @Test
    fun `보통 계좌는 성공`() {
        val result = service().transfer("t-1", "088", "1234567890", 10_000)
        assertEquals(InterbankOutcome.ACCEPTED, result.outcome)
        assertNull(result.rejectReason)
    }

    @Test
    fun `999로 시작하는 계좌는 계좌 없음으로 거절`() {
        val result = service().transfer("t-1", "088", "9990001", 10_000)
        assertEquals(InterbankOutcome.REJECTED, result.outcome)
        assertEquals(RejectReason.ACCOUNT_NOT_FOUND, result.rejectReason)
    }

    @Test
    fun `거절률 100이면 보통 계좌도 거절`() {
        val result = service(rejectRatePercent = 100).transfer("t-1", "088", "1234567890", 10_000)
        assertEquals(RejectReason.RECEIVING_BANK_REJECTED, result.rejectReason)
    }

    @Test
    fun `같은 키는 처음 판정을 그대로 - 거절률 때문에 결과가 바뀌지 않는다`() {
        // 무작위가 처음엔 성공, 다음엔 거절을 내도록 고정 → 같은 키 재요청은 다시 판정하지 않아야 한다
        val flipping = object : Random() {
            private var n = 0
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(until: Int) = if (n++ == 0) 99 else 0
        }
        val svc = service(rejectRatePercent = 50, random = flipping)

        val first = svc.transfer("t-1", "088", "1234567890", 10_000)
        val again = svc.transfer("t-1", "088", "1234567890", 10_000)

        assertEquals(InterbankOutcome.ACCEPTED, first.outcome)
        assertEquals(first, again)
    }

    @Test
    fun `같은 키에 다른 내용이면 충돌`() {
        val svc = service()
        svc.transfer("t-1", "088", "1234567890", 10_000)
        assertThrows<IdempotencyConflictException> { svc.transfer("t-1", "088", "1234567890", 20_000) }
    }

    @Test
    fun `같은 키가 동시에 와도 판정은 한 번 - 모두 같은 결과`() {
        val svc = service(rejectRatePercent = 50, random = Random.Default)
        val threads = 16
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val futures = (1..threads).map {
            pool.submit<InterbankOutcome> {
                start.await()
                svc.transfer("t-1", "088", "1234567890", 10_000).outcome
            }
        }
        start.countDown()
        val outcomes = futures.map { it.get(5, TimeUnit.SECONDS) }.toSet()
        pool.shutdown()

        assertEquals(1, outcomes.size, "같은 키인데 판정이 갈렸다: $outcomes")
    }
}
