package lab.bank.transfer.application

import lab.bank.transfer.FakeCoreConfiguration
import lab.bank.transfer.FakeExtractCompletedPublisher
import lab.bank.transfer.FakeExtractStorage
import lab.bank.transfer.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 대사 추출 (ADR-0006): 무엇을 싣나, 파일과 알림이 서로 맞나, 순서(올린 뒤 알림)를 지키나.
 * DB는 테스트끼리 공유 → 아무도 안 쓰는 먼 날짜(2001년)에 행을 만들어 그 날짜만 본다
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, FakeCoreConfiguration::class)
class DailyExtractUseCaseTest {

    @Autowired lateinit var useCase: DailyExtractUseCase
    @Autowired lateinit var storage: FakeExtractStorage
    @Autowired lateinit var publisher: FakeExtractCompletedPublisher
    @Autowired lateinit var jdbc: JdbcTemplate

    private val day = LocalDate.parse("2001-03-10")
    private val kstMidnight = Instant.parse("2001-03-09T15:00:00Z")   // 2001-03-10 00:00 KST

    @BeforeEach
    fun setUp() { storage.reset(); publisher.reset() }

    private fun domestic(createdAt: Instant, updatedAt: Instant = createdAt, status: String = "COMPLETED", amount: Long = 1_000): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO transfer.transfer (id, requester_id, idempotency_key, from_account_id, to_account_id, amount, status, failure_code, core_entry_id, created_at, updated_at, version, kind)
               VALUES (?, 'ext', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'DOMESTIC')""",
            id, "ext-$id", UUID.randomUUID(), UUID.randomUUID(), amount, status,
            if (status == "FAILED") "TEST" else null, if (status == "COMPLETED") UUID.randomUUID() else null,
            Timestamp.from(createdAt), Timestamp.from(updatedAt),
        )
        return id
    }

    private fun interbankRejected(createdAt: Instant): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO transfer.transfer (id, requester_id, idempotency_key, from_account_id, to_account_id, amount, status, failure_code, core_entry_id, created_at, updated_at, version, kind)
               VALUES (?, 'ext', ?, ?, NULL, 30000, 'CANCELLED', NULL, ?, ?, ?, 0, 'INTERBANK')""",
            id, "ext-$id", UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(createdAt), Timestamp.from(createdAt),
        )
        jdbc.update(
            """INSERT INTO transfer.interbank_transfer (transfer_id, to_bank_code, to_account_number, external_reject_reason, cancel_entry_id, created_at)
               VALUES (?, '004', '110-12,34', 'ACCOUNT_NOT_FOUND', ?, ?)""",
            id, UUID.randomUUID(), Timestamp.from(createdAt),
        )
        return id
    }

    @Test
    fun `그날 만들어졌거나 상태가 바뀐 송금을 싣고, 파일과 알림의 건수·합계·체크섬이 맞는다`() {
        val createdToday = domestic(kstMidnight.plusSeconds(60), amount = 1_000)
        val changedToday = domestic(kstMidnight.minusSeconds(3_600), updatedAt = kstMidnight.plusSeconds(120), amount = 2_000)  // 전날 생성, 오늘 해소 (결정 21)
        val interbank = interbankRejected(kstMidnight.plusSeconds(180))
        val yesterdayOnly = domestic(kstMidnight.minusSeconds(60))                       // 전날 생성·전날 끝: 안 실림
        val tomorrow = domestic(kstMidnight.plusSeconds(86_400))                         // 다음 날 00:00 KST: 안 실림 ([from, to))

        val result = useCase.extract(day)

        val event = publisher.events.single()
        val bytes = storage.objects.getValue(event.objectKey)
        val lines = String(bytes, Charsets.UTF_8).trimEnd().lines()
        val ids = lines.drop(1).map { it.substringBefore(",") }.toSet()

        assertEquals(setOf(createdToday, changedToday, interbank).map { it.toString() }.toSet(), ids.filter { it in setOf(createdToday, changedToday, interbank, yesterdayOnly, tomorrow).map(UUID::toString) }.toSet())
        assertEquals(lines.size - 1L, event.rowCount)
        assertEquals(result.rowCount, event.rowCount)
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), event.sha256)
        assertTrue(event.objectKey.startsWith("transfer/2001-03-10/"))
        assertEquals("transfer_id,kind,amount,status,created_at,updated_at,to_bank_code,to_account_number,external_reject_reason", lines.first())
        // 계좌번호에 쉼표가 있으면 따옴표로 감싼다 (RFC 4180)
        assertTrue(lines.any { it.startsWith(interbank.toString()) && it.contains(",\"110-12,34\",ACCOUNT_NOT_FOUND") }, lines.joinToString("\n"))
    }

    @Test
    fun `파일을 다 올린 뒤에 알림을 보낸다 - 다시 추출하면 새 키로 새 파일`() {
        domestic(kstMidnight.plusSeconds(60))
        // 올리는 순간, 이번 추출의 알림은 아직 없어야 한다 (= 지금까지 나간 알림 수 == 지금까지 올린 파일 수)
        storage.onPut = { assertEquals(storage.objects.size, publisher.events.size, "올리기 전에 알림이 나갔다") }

        val first = useCase.extract(day)
        val second = useCase.extract(day)

        assertTrue(first.objectKey != second.objectKey, "같은 키에 덮어쓰면 증거가 사라진다")
        assertEquals(2, storage.objects.size)
        assertEquals(2, publisher.events.size)
    }
}
