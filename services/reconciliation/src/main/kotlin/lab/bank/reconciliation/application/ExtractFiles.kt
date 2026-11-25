package lab.bank.reconciliation.application

import lab.bank.reconciliation.domain.CoreEntryRow
import lab.bank.reconciliation.domain.ExternalRow
import lab.bank.reconciliation.domain.TransferRow
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.UUID

/**
 * 추출 파일 검증 + 파싱 (결정 12·20). 파일이 알림의 trailer(건수·합계·체크섬)와 다르면 ExtractFileException.
 * 형식은 transfer TransferExtractCsv · core CoreExtractCsv와 같은 계약. 헤더가 다르면(생산자가 칸을 바꿨으면) 불량으로 본다
 */
object ExtractFiles {

    private val TRANSFER_HEADER = "transfer_id,kind,amount,status,created_at,updated_at,to_bank_code,to_account_number,external_reject_reason"
    private val CORE_HEADER = "entry_id,idempotency_key,type,amount,created_at,reverses_entry_id"

    fun verify(arrival: ExtractArrival, bytes: ByteArray): List<List<String>> {
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        if (sha != arrival.sha256) throw ExtractFileException("체크섬 다름: ${arrival.objectKey} (알림 ${arrival.sha256}, 파일 $sha)")
        val lines = String(bytes, Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw ExtractFileException("빈 파일 (헤더도 없음): ${arrival.objectKey}")
        val rows = lines.drop(1).map { parseCsvLine(it) }
        if (rows.size.toLong() != arrival.rowCount) throw ExtractFileException("건수 다름: 알림 ${arrival.rowCount}, 파일 ${rows.size}")
        return listOf(parseCsvLine(lines.first())) + rows
    }

    fun parseTransfers(arrival: ExtractArrival, bytes: ByteArray): List<TransferRow> {
        val all = verify(arrival, bytes)
        if (all.first().joinToString(",") != TRANSFER_HEADER) throw ExtractFileException("transfer 파일 헤더가 계약과 다름: ${all.first()}")
        val rows = all.drop(1).map {
            try {
                TransferRow(UUID.fromString(it[0]), it[1], it[2].toLong(), it[3], Instant.parse(it[4]), Instant.parse(it[5]), it[6].ifEmpty { null }, it[7].ifEmpty { null }, it[8].ifEmpty { null })
            } catch (e: Exception) { throw ExtractFileException("transfer 파일 줄을 읽을 수 없음: $it", e) }
        }
        checkSum(arrival, rows.sumOf { it.amount })
        return rows
    }

    fun parseCoreEntries(arrival: ExtractArrival, bytes: ByteArray): List<CoreEntryRow> {
        val all = verify(arrival, bytes)
        if (all.first().joinToString(",") != CORE_HEADER) throw ExtractFileException("core 파일 헤더가 계약과 다름: ${all.first()}")
        val rows = all.drop(1).map {
            try {
                CoreEntryRow(UUID.fromString(it[0]), it[1], it[2], it[3].toLong(), Instant.parse(it[4]), it[5].ifEmpty { null }?.let(UUID::fromString))
            } catch (e: Exception) { throw ExtractFileException("core 파일 줄을 읽을 수 없음: $it", e) }
        }
        checkSum(arrival, rows.sumOf { it.amount })
        return rows
    }

    /**
     * 외부 정산 파일 번역 (ACL, bank-mock SettlementFile 형식: H/D/T 레코드, "|" 구분, 처리 시각 yyyyMMddHHmmss Asia/Seoul).
     * 받을 때(수신 작업)와 대사할 때 둘 다 쓴다: 받을 때는 trailer로 "다 왔나"를, 대사할 때는 우리가 기록한 체크섬으로 "그 파일 그대로인가"를 본다
     */
    fun parseExternal(bytes: ByteArray): List<ExternalRow> {
        val lines = String(bytes, Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        if (lines.size < 2 || !lines.first().startsWith("H|") || !lines.last().startsWith("T|")) throw ExtractFileException("외부 파일 형식이 아님 (헤더·트레일러 없음)")
        val rows = lines.drop(1).dropLast(1).map { line ->
            val f = line.split("|")
            if (f.size != 8 || f[0] != "D") throw ExtractFileException("외부 파일 건별 줄 형식이 아님: $line")
            try {
                ExternalRow(f[1], f[2], f[3], f[4].toLong(), f[5] == "A", f[6].ifEmpty { null },
                    LocalDateTime.parse(f[7], EXTERNAL_TIME).atZone(EXTERNAL_ZONE).toInstant())
            } catch (e: Exception) { throw ExtractFileException("외부 파일 줄을 읽을 수 없음: $line", e) }
        }
        val trailer = lines.last().split("|")
        if (trailer.size != 3 || trailer[1].toLong() != rows.size.toLong() || trailer[2].toLong() != rows.sumOf { it.amount }) {
            throw ExtractFileException("외부 파일 트레일러와 내용이 다름: ${lines.last()} (건수 ${rows.size}, 합계 ${rows.sumOf { it.amount }})")
        }
        return rows
    }

    fun parseExternal(arrival: ExtractArrival, bytes: ByteArray): List<ExternalRow> {
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        if (sha != arrival.sha256) throw ExtractFileException("체크섬 다름: ${arrival.objectKey}")
        return parseExternal(bytes)
    }

    private val EXTERNAL_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val EXTERNAL_ZONE = ZoneId.of("Asia/Seoul")

    private fun checkSum(arrival: ExtractArrival, sum: Long) {
        if (sum != arrival.amountSum) throw ExtractFileException("금액 합계 다름: 알림 ${arrival.amountSum}, 파일 $sum")
    }

    // RFC 4180 한 줄 파싱 (따옴표 안 쉼표, "" → "). 값 안 줄바꿈은 생산자가 만들지 않는다
    fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
