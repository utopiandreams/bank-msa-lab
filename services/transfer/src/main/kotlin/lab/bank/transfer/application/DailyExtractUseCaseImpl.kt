package lab.bank.transfer.application

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.transfer.application.port.out.ExtractCompletedPublisher
import lab.bank.transfer.application.port.out.ExtractStorage
import lab.bank.transfer.application.port.out.TransferExtractSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 순서가 계약이다 (설계 연습 09 결정 11·12): DB 커서로 읽으며 임시 파일에 흘려 쓰기 → **저장소에 올리기 → 그 다음에 이벤트**.
 * 이벤트를 먼저 보내면 대사 서비스가 받았는데 파일이 없을 수 있다. 올리기가 성공하고 이벤트가 실패하면
 * 고아 파일이 하나 남지만 해가 없다 (아무도 가리키지 않는 파일. 다시 추출하면 새 키로 새 파일)
 *
 * 메모리: 건수와 무관 (2026-11-25 개발자 지적: 처음엔 리스트 → 문자열 → 바이트로 같은 데이터를 여러 벌 들고 있어 100만 건이면 OOM).
 *   DB는 커서로 조금씩(JdbcTransferExtractSource), 줄마다 바로 파일에(ExtractFileWriter), 저장소엔 파일을 올린다
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
        val extractId = UUID.randomUUID()
        val objectKey = ExtractCompletedEvent.objectKey(ExtractCompletedEvent.SOURCE_TRANSFER, businessDate, extractId)

        val event = ExtractFileWriter.open(TransferExtractCsv.HEADER).use { file ->
            source.forEachChangedBetween(from, to) { row -> file.append(TransferExtractCsv.line(row), row.amount) }
            val sha256 = file.finish()
            storage.putFile(objectKey, file.file)
            ExtractCompletedEvent(
                eventId = UUID.randomUUID(),
                schemaVersion = ExtractCompletedEvent.SCHEMA_VERSION,
                source = ExtractCompletedEvent.SOURCE_TRANSFER,
                businessDate = businessDate,
                extractId = extractId,
                objectKey = objectKey,
                rowCount = file.rowCount,
                amountSum = file.amountSum,
                sha256 = sha256,
                extractedAt = Instant.now(),
            )
        }
        publisher.publish(event)
        log.info("대사 추출 완료 date={} rows={} key={}", businessDate, event.rowCount, objectKey)
        return ExtractResult(extractId, objectKey, event.rowCount, event.amountSum)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DailyExtractUseCaseImpl::class.java)
    }
}
