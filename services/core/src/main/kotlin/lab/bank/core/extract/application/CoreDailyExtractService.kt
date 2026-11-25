package lab.bank.core.extract.application

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.core.ledger.application.LedgerUseCase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/**
 * core 일자별 대사 추출 (ADR-0006). extract 모듈은 ledger의 공개 인터페이스(LedgerUseCase)만 쓴다 (테이블에 직접 접근하지 않음).
 *
 * 싣는 전표: 송금이 있어야 하는 종류만 (TRANSFER · INTERBANK_WITHDRAWAL · INTERBANK_WITHDRAWAL_CANCEL, 설계 연습 09 결정 3).
 *   입금·출금(DEPOSIT·WITHDRAWAL)은 transfer를 거치지 않는 게 정상이라 싣지 않는다.
 * 시산표: 그날 **모든** 전표의 차변 합·대변 합을 알림에 담는다 (파일엔 송금 전표만 있으니 파일로는 못 구한다).
 * 순서: 저장소에 올린 뒤 알림 (transfer DailyExtractUseCaseImpl과 같은 계약)
 */
@Service
class CoreDailyExtractService(
    private val ledger: LedgerUseCase,
    private val storage: ExtractStorage,
    private val publisher: ExtractCompletedPublisher,
) {

    fun extract(businessDate: LocalDate): ExtractCompletedEvent {
        val from = businessDate.atStartOfDay(ExtractCompletedEvent.BUSINESS_ZONE).toInstant()
        val to = businessDate.plusDays(1).atStartOfDay(ExtractCompletedEvent.BUSINESS_ZONE).toInstant()
        val rows = ledger.entriesCreatedBetween(from, to, TRANSFER_ENTRY_TYPES)
        val totals = ledger.totalsBetween(from, to)
        val bytes = CoreExtractCsv.write(rows).toByteArray(Charsets.UTF_8)

        val extractId = UUID.randomUUID()
        val objectKey = ExtractCompletedEvent.objectKey(ExtractCompletedEvent.SOURCE_CORE, businessDate, extractId)
        storage.put(objectKey, bytes)

        val event = ExtractCompletedEvent(
            eventId = UUID.randomUUID(),
            schemaVersion = ExtractCompletedEvent.SCHEMA_VERSION,
            source = ExtractCompletedEvent.SOURCE_CORE,
            businessDate = businessDate,
            extractId = extractId,
            objectKey = objectKey,
            rowCount = rows.size.toLong(),
            amountSum = rows.sumOf { it.amount },
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            extractedAt = Instant.now(),
            debitSum = totals.debitSum,
            creditSum = totals.creditSum,
        )
        publisher.publish(event)
        log.info("대사 추출 완료 date={} rows={} debit={} credit={} key={}", businessDate, event.rowCount, totals.debitSum, totals.creditSum, objectKey)
        return event
    }

    companion object {
        private val log = LoggerFactory.getLogger(CoreDailyExtractService::class.java)
        val TRANSFER_ENTRY_TYPES = setOf("TRANSFER", "INTERBANK_WITHDRAWAL", "INTERBANK_WITHDRAWAL_CANCEL")
    }
}
