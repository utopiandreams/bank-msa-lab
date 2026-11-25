package lab.bank.transfer.application

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.transfer.application.port.out.ExtractCompletedPublisher
import lab.bank.transfer.application.port.out.ExtractStorage
import lab.bank.transfer.application.port.out.TransferExtractSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/**
 * 순서가 계약이다 (설계 연습 09 결정 11·12): 읽기 → CSV → **저장소에 올리기 → 그 다음에 이벤트**.
 * 이벤트를 먼저 보내면 대사 서비스가 받았는데 파일이 없을 수 있다. 올리기가 성공하고 이벤트가 실패하면
 * 고아 파일이 하나 남지만 해가 없다 (아무도 가리키지 않는 파일. 다시 추출하면 새 키로 새 파일)
 *
 * 트랜잭션이 없다: DB는 읽기만 하고(그 순간의 스냅숏), 저장소·Kafka는 어차피 DB 트랜잭션으로 묶이지 않는다
 */
@Service
class DailyExtractUseCaseImpl(
    private val source: TransferExtractSource,
    private val storage: ExtractStorage,
    private val publisher: ExtractCompletedPublisher,
) : DailyExtractUseCase {

    override fun extract(businessDate: LocalDate): ExtractResult {
        val from = businessDate.atStartOfDay(ExtractCompletedEvent.BUSINESS_ZONE).toInstant()
        val to = businessDate.plusDays(1).atStartOfDay(ExtractCompletedEvent.BUSINESS_ZONE).toInstant()
        val rows = source.rowsChangedBetween(from, to)
        val bytes = TransferExtractCsv.write(rows).toByteArray(Charsets.UTF_8)

        val extractId = UUID.randomUUID()
        val objectKey = ExtractCompletedEvent.objectKey(ExtractCompletedEvent.SOURCE_TRANSFER, businessDate, extractId)
        storage.put(objectKey, bytes)

        val event = ExtractCompletedEvent(
            eventId = UUID.randomUUID(),
            schemaVersion = ExtractCompletedEvent.SCHEMA_VERSION,
            source = ExtractCompletedEvent.SOURCE_TRANSFER,
            businessDate = businessDate,
            extractId = extractId,
            objectKey = objectKey,
            rowCount = rows.size.toLong(),
            amountSum = rows.sumOf { it.amount },
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            extractedAt = Instant.now(),
        )
        publisher.publish(event)
        log.info("대사 추출 완료 date={} rows={} key={}", businessDate, event.rowCount, objectKey)
        return ExtractResult(extractId, objectKey, event.rowCount, event.amountSum)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DailyExtractUseCaseImpl::class.java)
    }
}
