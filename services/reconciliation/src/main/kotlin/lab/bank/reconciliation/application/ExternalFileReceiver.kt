package lab.bank.reconciliation.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID

/**
 * 외부 정산 파일 수신 작업 (설계 연습 09 결정 19: 원래는 대외계 몫, 서버를 늘리지 않으려고 대사 서비스에 둔다).
 *   1. 외부 채널에서 받아 온다
 *   2. **트레일러로 다 왔나 확인** (번역도 한 번 해 본다: 형식이 깨졌으면 지금 알아야 한다)
 *   3. **원본 그대로** 저장소에 보관 (저쪽이 이렇게 보냈다는 증거. 우리 형식으로 바꾼 파일은 증거가 못 된다)
 *   4. 인박스에 "외부 D일 도착" 기록 → 대사 일꾼이 transfer 추출과 짝지어 돈다
 */
@Service
class ExternalFileReceiver(
    private val client: ExternalSettlementClient,
    private val writer: ExtractFileWriter,
    private val repository: ReconRepository,
) {

    fun receive(date: LocalDate): ExtractArrival {
        val bytes = client.fetch(date)
        val rows = ExtractFiles.parseExternal(bytes)
        val extractId = UUID.randomUUID()
        val key = "$SOURCE/$date/$extractId.txt"
        writer.put(key, bytes)
        val arrival = ExtractArrival(extractId, SOURCE, date, key, rows.size.toLong(), rows.sumOf { it.amount },
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), null, null, Instant.now(), Instant.now())
        repository.insertArrival(arrival)
        log.info("외부 정산 파일 수신 date={} rows={} key={}", date, rows.size, key)
        return arrival
    }

    companion object {
        private val log = LoggerFactory.getLogger(ExternalFileReceiver::class.java)
        const val SOURCE = "external"
    }
}
