package lab.bank.reconciliation.application

import lab.bank.reconciliation.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 대사 회차 (ADR-0006): 인박스 → 파일 검증 → 판정 → 이월·경보 → 회차 기록, 다시 돌리기, 파일 불량, 시산표.
 * 파일은 가짜 저장소(메모리), DB는 진짜 Postgres. 이월 풀이 날짜를 가리지 않아 테스트마다 테이블을 비운다
 */
@SpringBootTest(properties = ["reconciliation.worker.enabled=false"])
@Import(TestcontainersConfiguration::class, ReconRunServiceTest.Files::class)
class ReconRunServiceTest {

    class MemoryFiles : ExtractFileReader {
        val objects = mutableMapOf<String, ByteArray>()
        override fun read(objectKey: String): ByteArray = objects[objectKey] ?: throw ExtractFileException("없음: $objectKey")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Files { @Bean @Primary fun memoryFiles() = MemoryFiles() }

    @Autowired lateinit var service: ReconRunService
    @Autowired lateinit var repository: ReconRepository
    @Autowired lateinit var files: MemoryFiles
    @Autowired lateinit var jdbc: JdbcClient

    private val d1 = LocalDate.parse("2026-11-20")
    private val d2 = d1.plusDays(1)
    private val d3 = d1.plusDays(2)
    private val t0 = Instant.parse("2026-11-20T01:00:00Z")

    @BeforeEach
    fun clean() {
        jdbc.sql("TRUNCATE reconciliation.extract_arrival, reconciliation.recon_run, reconciliation.recon_item").update()
        files.objects.clear()
    }

    // ---- 파일 만들기 ----
    private fun tRow(id: UUID, kind: String, status: String, amount: Long = 10_000) = "$id,$kind,$amount,$status,$t0,$t0,,,"
    private fun cRow(key: String, type: String, amount: Long = 10_000, entryId: UUID = UUID.randomUUID(), reverses: UUID? = null) =
        "$entryId,$key,$type,$amount,$t0,${reverses ?: ""}"

    private fun arrive(source: String, date: LocalDate, header: String, rows: List<String>, debit: Long? = null, credit: Long? = null, tamper: Boolean = false): ExtractArrival {
        val bytes = (listOf(header) + rows).joinToString("\n", postfix = "\n").toByteArray()
        val key = "$source/$date/${UUID.randomUUID()}.csv"
        files.objects[key] = if (tamper) bytes + "x".toByteArray() else bytes
        val amountIdx = header.split(",").indexOf("amount")
        val arrival = ExtractArrival(UUID.randomUUID(), source, date, key, rows.size.toLong(), rows.sumOf { it.split(",")[amountIdx].toLong() },
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), debit, credit, t0, Instant.now())
        assertTrue(repository.insertArrival(arrival))
        return arrival
    }

    private val tHeader = "transfer_id,kind,amount,status,created_at,updated_at,to_bank_code,to_account_number,external_reject_reason"
    private val cHeader = "entry_id,idempotency_key,type,amount,created_at,reverses_entry_id"
    private fun day(date: LocalDate, transfers: List<String>, entries: List<String>, debit: Long = 100, credit: Long = 100) {
        arrive("transfer", date, tHeader, transfers)
        arrive("core", date, cHeader, entries, debit, credit)
    }

    private fun item(key: String) = jdbc.sql("SELECT kind, status FROM reconciliation.recon_item WHERE item_key = :k ORDER BY updated_at DESC LIMIT 1")
        .param("k", key).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)

    private fun runStatus(date: LocalDate) = jdbc.sql("SELECT status FROM reconciliation.recon_run WHERE business_date = :d ORDER BY finished_at")
        .param("d", Date.valueOf(date)).query(String::class.java).list()

    @Test
    fun `첫날 - 정상은 남기지 않고, 끝나지 않은 송금은 이월, 실패인데 돈이 움직였거나 송금 없는 전표는 즉시 경보`() {
        val ok = UUID.randomUUID(); val pending = UUID.randomUUID(); val failedButMoved = UUID.randomUUID(); val orphan = UUID.randomUUID()
        day(d1,
            listOf(tRow(ok, "DOMESTIC", "COMPLETED"), tRow(pending, "INTERBANK", "WITHDRAW_CANCEL_REQUESTED"), tRow(failedButMoved, "DOMESTIC", "FAILED")),
            listOf(cRow("$ok", "TRANSFER"), cRow("$pending", "INTERBANK_WITHDRAWAL"), cRow("$failedButMoved", "TRANSFER"), cRow("$orphan", "TRANSFER")))

        assertEquals(1, service.runPending())

        assertEquals(null, item("$ok"))
        assertEquals("PENDING" to "OPEN", item("$pending"))
        assertEquals("UNEXPECTED_ENTRY" to "ESCALATED", item("$failedButMoved"))
        assertEquals("UNEXPECTED_ENTRY" to "ESCALATED", item("$orphan"))
        assertEquals(listOf("DONE"), runStatus(d1))
        assertEquals(0, service.runPending(), "같은 추출 조합으로는 다시 돌지 않는다")
    }

    @Test
    fun `이월 - 다음 날 끝 상태가 되면 풀리고, 하루 넘게 안 풀리면 경보`() {
        val pending = UUID.randomUUID(); val missing = UUID.randomUUID()
        val withdrawal = UUID.randomUUID()
        day(d1, listOf(tRow(pending, "INTERBANK", "WITHDRAW_CANCEL_REQUESTED"), tRow(missing, "DOMESTIC", "COMPLETED")),
            listOf(cRow("$pending", "INTERBANK_WITHDRAWAL", entryId = withdrawal)))
        service.runPending()
        assertEquals("MISSING_ENTRY" to "OPEN", item("$missing"), "자정 경계일 수 있어 첫날은 이월")

        // 둘째 날: 보상이 끝나 CANCELLED로 다시 실리고 취소 전표가 생김. missing의 전표는 여전히 없음
        day(d2, listOf(tRow(pending, "INTERBANK", "CANCELLED")), listOf(cRow("$pending-cancel", "INTERBANK_WITHDRAWAL_CANCEL", reverses = withdrawal)))
        service.runPending()

        assertEquals("PENDING" to "RESOLVED", item("$pending"))
        assertEquals("MISSING_ENTRY" to "ESCALATED", item("$missing"), "하루 넘게 안 풀림 → 경보 (결정 14)")
    }

    @Test
    fun `파일이 알림과 다르면 대사 불일치가 아니라 회차 실패 - 항목을 만들지 않는다`() {
        arrive("transfer", d1, tHeader, listOf(tRow(UUID.randomUUID(), "DOMESTIC", "COMPLETED")), tamper = true)
        arrive("core", d1, cHeader, emptyList(), 0, 0)

        service.runPending()

        assertEquals(listOf("FAILED"), runStatus(d1))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM reconciliation.recon_item").query(Int::class.java).single())
        assertEquals(0, service.runPending(), "같은 불량 추출로는 다시 돌지 않는다 (새 추출을 기다린다)")
    }

    @Test
    fun `같은 날짜에 새 추출이 오면 다시 돌린다 - 이전 회차는 SUPERSEDED, 이전 결과는 되돌린 뒤 새로 판정`() {
        val id = UUID.randomUUID()
        day(d1, listOf(tRow(id, "DOMESTIC", "COMPLETED")), emptyList())
        service.runPending()
        assertEquals("MISSING_ENTRY" to "OPEN", item("$id"))

        // core가 버그를 고쳐 다시 추출: 이번엔 전표가 있다
        arrive("core", d1, cHeader, listOf(cRow("$id", "TRANSFER")), 100, 100)
        service.runPending()

        assertEquals(listOf("SUPERSEDED", "DONE"), runStatus(d1))
        assertEquals(null, item("$id"), "이전 회차가 만든 항목은 지워지고, 새 판정은 정상")
    }

    @Test
    fun `지난 날짜는 다시 돌리지 않는다 (뒤 날짜의 이월이 꼬임)`() {
        day(d1, emptyList(), emptyList()); service.runPending()
        day(d2, emptyList(), emptyList()); service.runPending()

        arrive("core", d1, cHeader, emptyList(), 0, 0)   // d1을 다시 추출
        service.runPending()

        assertEquals(listOf("DONE", "FAILED"), runStatus(d1))
    }

    @Test
    fun `시산표 - 그날 원장 차변 합과 대변 합이 다르면 즉시 경보`() {
        day(d3, emptyList(), emptyList(), debit = 100, credit = 90)
        service.runPending()
        assertEquals("TRIAL_BALANCE" to "ESCALATED", item("trial:$d3"))
    }

    @Test
    fun `같은 추출 알림이 두 번 와도 인박스엔 한 번 (멱등 컨슈머)`() {
        val a = arrive("transfer", d1, tHeader, emptyList())
        assertFalse(repository.insertArrival(a))
    }
}
