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
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 외부 쌍 (ADR-0006 결정 7·19): 수신 작업이 외부 원본을 받아 보관·도착 기록 → 대사 일꾼이 타행 송금과 판정.
 * 장면: 해소 일꾼이 외부 조회 "없음"을 믿고 출금을 취소했는데, 외부는 실제로 입금했다 (조회 계약 위반) → 이중 지급 경보
 */
@SpringBootTest(properties = ["reconciliation.worker.enabled=false", "reconciliation.external.enabled=false"])
@Import(TestcontainersConfiguration::class, ExternalPairTest.Fakes::class)
class ExternalPairTest {

    class MemoryStore : ExtractFileReader, ExtractFileWriter {
        val objects = mutableMapOf<String, ByteArray>()
        override fun read(objectKey: String) = objects[objectKey] ?: throw ExtractFileException("없음: $objectKey")
        override fun put(objectKey: String, content: ByteArray) { objects[objectKey] = content }
    }

    class FakeBank : ExternalSettlementClient {
        var file: String = ""
        override fun fetch(date: LocalDate) = file.toByteArray()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        @Bean @Primary fun memoryStore() = MemoryStore()
        @Bean @Primary fun fakeBank() = FakeBank()
    }

    @Autowired lateinit var receiver: ExternalFileReceiver
    @Autowired lateinit var service: ReconRunService
    @Autowired lateinit var repository: ReconRepository
    @Autowired lateinit var store: MemoryStore
    @Autowired lateinit var bank: FakeBank
    @Autowired lateinit var jdbc: JdbcClient

    private val date = LocalDate.parse("2026-11-20")
    private val t0 = Instant.parse("2026-11-20T01:00:00Z")

    @BeforeEach
    fun clean() {
        jdbc.sql("TRUNCATE reconciliation.extract_arrival, reconciliation.recon_run, reconciliation.recon_item").update()
        store.objects.clear()
    }

    private fun transferFile(vararg rows: String) {
        val header = "transfer_id,kind,amount,status,created_at,updated_at,to_bank_code,to_account_number,external_reject_reason"
        val bytes = (listOf(header) + rows).joinToString("\n", postfix = "\n").toByteArray()
        val key = "transfer/$date/${UUID.randomUUID()}.csv"
        store.objects[key] = bytes
        repository.insertArrival(ExtractArrival(UUID.randomUUID(), "transfer", date, key, rows.size.toLong(), rows.sumOf { it.split(",")[2].toLong() },
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), null, null, t0, Instant.now()))
    }

    private fun item(key: String) = jdbc.sql("SELECT kind, status FROM reconciliation.recon_item WHERE item_key = :k AND pair = 'TRANSFER_EXTERNAL'")
        .param("k", key).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)

    @Test
    fun `외부 원본을 그대로 보관하고, 조회 '없음'으로 취소한 송금이 외부에선 입금됐으면 이중 지급 경보`() {
        val ok = UUID.randomUUID(); val doublePaid = UUID.randomUUID()
        transferFile(
            "$ok,INTERBANK,30000,COMPLETED,$t0,$t0,004,110-1,",
            "$doublePaid,INTERBANK,50000,CANCELLED,$t0,$t0,004,110-2,",   // 사유 없음 = 외부 조회 "없음"으로 취소
        )
        bank.file = """
            H|20261120|BANKMOCK
            D|$ok|004|110-1|30000|A||20261120100000
            D|$doublePaid|004|110-2|50000|A||20261120100001
            T|2|80000
        """.trimIndent() + "\n"

        val arrival = receiver.receive(date)
        service.runPending()

        assertTrue(arrival.objectKey.startsWith("external/2026-11-20/"))
        assertEquals(bank.file, String(store.objects.getValue(arrival.objectKey)), "원본 그대로 보관 (증거)")
        assertEquals(null, item("$ok"))
        assertEquals("DOUBLE_PAYMENT" to "ESCALATED", item("$doublePaid"))
    }

    @Test
    fun `트레일러와 내용이 다르면 받지 않는다 (파일이 잘림)`() {
        bank.file = "H|20261120|BANKMOCK\nD|x|004|1|100|A||20261120100000\nT|2|200\n"
        assertFailsWith<ExtractFileException> { receiver.receive(date) }
        assertEquals(0, jdbc.sql("SELECT count(*) FROM reconciliation.extract_arrival").query(Int::class.java).single())
    }
}
