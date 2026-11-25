package lab.bank.reconciliation.application

import lab.bank.reconciliation.domain.CoreEntryRow
import lab.bank.reconciliation.domain.TransferRow
import java.security.MessageDigest
import java.time.Instant
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
