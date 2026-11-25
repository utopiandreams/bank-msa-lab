package lab.bank.bankmock.application

import lab.bank.bankmock.domain.InterbankOutcome
import lab.bank.bankmock.domain.RejectReason
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterbankTransferServiceTest {

    private fun svc(props: BankMockProperties, random: Random = Random(42), sleeper: (Duration) -> Unit = {}) =
        InterbankTransferService(InMemoryInterbankTransferStore(), BankMockSwitches(props), random, sleeper)

    private fun service(rejectRatePercent: Int = 0, random: Random = Random(42)) =
        svc(BankMockProperties(rejectRatePercent = rejectRatePercent), random)

    // 실제로 기다리지 않고 "얼마나 기다렸나"만 기록
    private val slept = mutableListOf<Duration>()

    private fun delayed(processing: Duration = Duration.ZERO, response: Duration = Duration.ZERO, deadline: Duration = Duration.ofSeconds(10)) =
        svc(BankMockProperties(processingDelay = processing, responseDelay = response, processingDeadline = deadline), sleeper = { slept += it })

    @Test
    fun `처리 지연이 상한 안이면 처리한다 - 조회하면 있음`() {
        val svc = delayed(processing = Duration.ofSeconds(2))
        svc.transfer("t-1", "088", "1234567890", 10_000)
        assertEquals(listOf(Duration.ofSeconds(2)), slept)
        assertEquals(InterbankOutcome.ACCEPTED, svc.find("t-1")?.outcome)
    }

    @Test
    fun `처리 지연이 상한을 넘으면 상한까지만 기다리고 포기 - 기록 없음`() {
        val svc = delayed(processing = Duration.ofSeconds(30), deadline = Duration.ofSeconds(10))
        assertThrows<ProcessingDeadlineExceededException> { svc.transfer("t-1", "088", "1234567890", 10_000) }
        assertEquals(listOf(Duration.ofSeconds(10)), slept)
        assertNull(svc.find("t-1"), "포기했으면 조회해도 없어야 한다 (보내는 쪽이 이 '없음'을 믿고 출금을 취소한다)")
    }

    @Test
    fun `응답 지연은 기록한 뒤 - 응답을 못 받아도 조회하면 있음`() {
        // 보내는 쪽 read-timeout보다 긴 응답 지연 = "외부는 처리했는데 우리는 모름". 기다리는 순간 이미 기록돼 있어야 한다
        lateinit var svc: InterbankTransferService
        val recordedWhileWaiting = mutableListOf<Boolean>()
        svc = svc(BankMockProperties(responseDelay = Duration.ofSeconds(5)), sleeper = { recordedWhileWaiting += (svc.find("t-1") != null) })
        svc.transfer("t-1", "088", "1234567890", 10_000)
        assertEquals(listOf(true), recordedWhileWaiting)
    }

    @Test
    fun `이미 판정한 키의 재요청은 지연 없이 - 처리 상한에 걸려 포기로 보이지 않는다`() {
        val svc = delayed(processing = Duration.ofSeconds(2))
        svc.transfer("t-1", "088", "1234567890", 10_000)
        slept.clear()
        svc.transfer("t-1", "088", "1234567890", 10_000)
        assertEquals(emptyList(), slept)
    }

    @Test
    fun `정산 파일 - 그날 판정한 것만 성공·거절 결과와 함께, 트레일러에 건수·합계`() {
        val svc = service()
        svc.transfer("t-ok", "088", "1234567890", 10_000)
        svc.transfer("t-no", "088", "9990001", 5_000)
        val today = LocalDate.now(SettlementFile.ZONE)

        val lines = SettlementFile.write(today, svc.processedOn(today)).trimEnd().lines()

        assertEquals("H|${today.toString().replace("-", "")}|BANKMOCK", lines.first())
        assertTrue(lines.any { it.startsWith("D|t-ok|088|1234567890|10000|A||") })
        assertTrue(lines.any { it.startsWith("D|t-no|088|9990001|5000|R|ACCOUNT_NOT_FOUND|") })
        assertEquals("T|2|15000", lines.last())
        assertTrue(svc.processedOn(today.minusDays(1)).isEmpty())
    }

    @Test
    fun `조회 계약 위반 스위치 - 처리했는데 조회엔 없음, 정산 파일엔 나온다`() {
        val svc = svc(BankMockProperties(lookupLiesNotFound = true))
        svc.transfer("t-1", "088", "1234567890", 10_000)
        assertNull(svc.find("t-1"))
        assertEquals(1, svc.processedOn(LocalDate.now(SettlementFile.ZONE)).size)
    }

    @Test
    fun `받은 적 없는 키는 조회하면 없음`() {
        assertNull(service().find("never"))
    }

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
    fun `응답 지연 비율 - 요청의 일부에만 지연을 준다`() {
        // 요청 하나에 난수 2번: 판정(거절률 0이라 결과 무관) → 지연 여부(30 미만이면 지연). 지연 여부 자리에 0, 50, 0, 50 → 4건 중 2건
        val draws = ArrayDeque(listOf(50, 0, 50, 50, 50, 0, 50, 50))
        val scripted = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextInt(until: Int) = draws.removeFirst()
        }
        val svc = svc(BankMockProperties(responseDelay = Duration.ofSeconds(5), responseDelayPercent = 30), scripted, sleeper = { slept += it })
        repeat(4) { svc.transfer("t-$it", "088", "1234567890", 10_000) }
        assertEquals(2, slept.size)
    }

    @Test
    fun `스위치를 실행 중에 바꾸면 다음 요청부터 반영된다`() {
        val switches = BankMockSwitches(BankMockProperties())
        val svc = InterbankTransferService(InMemoryInterbankTransferStore(), switches, Random(42), sleeper = { slept += it })
        svc.transfer("t-1", "088", "1234567890", 10_000)
        switches.change { it.copy(responseDelay = Duration.ofSeconds(5)) }
        svc.transfer("t-2", "088", "1234567890", 10_000)
        assertEquals(listOf(Duration.ofSeconds(5)), slept)
    }

    @Test
    fun `스위치에 잘못된 값이면 바뀌지 않는다`() {
        val switches = BankMockSwitches(BankMockProperties())
        assertThrows<IllegalArgumentException> { switches.change { it.copy(responseDelayPercent = 150) } }
        assertEquals(100, switches.current.responseDelayPercent)
    }
}
