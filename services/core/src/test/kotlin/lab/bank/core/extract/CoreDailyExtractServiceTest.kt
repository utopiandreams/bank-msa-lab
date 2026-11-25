package lab.bank.core.extract

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.core.TestcontainersConfiguration
import lab.bank.core.extract.application.CoreDailyExtractService
import lab.bank.core.extract.application.ExtractCompletedPublisher
import lab.bank.core.extract.application.ExtractStorage
import lab.bank.core.ledger.application.LedgerUseCase
import lab.bank.core.shared.Amount
import lab.bank.core.shared.IdempotencyKey
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * core 대사 추출 (ADR-0006): 송금 전표만 싣는다, 취소 전표는 원래 전표를 가리킨다, 시산표 합계, 파일과 알림이 맞는다.
 * DB를 테스트끼리 공유해 오늘 날짜에 다른 테스트의 전표도 섞인다 → 이 테스트가 만든 키만 본다
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, CoreDailyExtractServiceTest.Fakes::class)
class CoreDailyExtractServiceTest {

    @TestConfiguration(proxyBeanMethods = false)
    class Fakes {
        @Bean @Primary fun fakeStorage() = FakeStorage()
        @Bean @Primary fun fakePublisher() = FakePublisher()
    }

    class FakeStorage : ExtractStorage {
        val objects = linkedMapOf<String, ByteArray>()
        override fun putFile(objectKey: String, file: Path) { objects[objectKey] = Files.readAllBytes(file) }
    }

    class FakePublisher : ExtractCompletedPublisher {
        val events = mutableListOf<ExtractCompletedEvent>()
        override fun publish(event: ExtractCompletedEvent) { events += event }
    }

    @Autowired lateinit var service: CoreDailyExtractService
    @Autowired lateinit var ledger: LedgerUseCase
    @Autowired lateinit var storage: FakeStorage
    @Autowired lateinit var publisher: FakePublisher

    @Test
    fun `송금 전표만 싣고 취소는 원래 전표를 가리킨다 - 시산표 차변 합 = 대변 합`() {
        val domesticId = UUID.randomUUID().toString()
        val interbankId = UUID.randomUUID().toString()
        val depositKey = "dep-" + UUID.randomUUID()
        ledger.recordDeposit(IdempotencyKey(depositKey), UUID.randomUUID(), Amount(50_000))
        ledger.recordTransfer(IdempotencyKey(domesticId), UUID.randomUUID(), UUID.randomUUID(), Amount(1_000))
        val withdrawal = ledger.recordInterbankWithdraw(IdempotencyKey(interbankId), UUID.randomUUID(), Amount(30_000))
        ledger.recordInterbankWithdrawCancel(IdempotencyKey(interbankId))

        val event = service.extract(LocalDate.now(ExtractCompletedEvent.BUSINESS_ZONE))

        val bytes = storage.objects.getValue(event.objectKey)
        val lines = String(bytes, Charsets.UTF_8).trimEnd().lines()
        assertEquals("entry_id,idempotency_key,type,amount,created_at,reverses_entry_id", lines.first())
        val byKey = lines.drop(1).map { it.split(",") }.associateBy { it[1] }

        assertTrue(depositKey !in byKey, "입금은 transfer를 거치지 않으니 싣지 않는다")
        assertEquals(listOf("TRANSFER", "1000"), byKey.getValue(domesticId).subList(2, 4))
        assertEquals(listOf("INTERBANK_WITHDRAWAL", "30000"), byKey.getValue(interbankId).subList(2, 4))
        val cancel = byKey.getValue("$interbankId-cancel")
        assertEquals(listOf("INTERBANK_WITHDRAWAL_CANCEL", "30000"), cancel.subList(2, 4))
        assertEquals(withdrawal.entryId.toString(), cancel[5], "취소는 원래 출금 전표를 가리킨다")

        assertEquals(lines.size - 1L, event.rowCount)
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), event.sha256)
        assertEquals(event.debitSum, event.creditSum, "복식부기: 그날 원장의 차변 합 = 대변 합")
        assertTrue(event.debitSum!! >= 50_000 + 1_000 + 30_000 + 30_000, "입금을 포함한 모든 전표가 시산표에 들어간다")
        assertTrue(event.objectKey.startsWith("core/"))
    }
}
