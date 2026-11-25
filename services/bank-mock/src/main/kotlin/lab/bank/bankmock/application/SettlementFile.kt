package lab.bank.bankmock.application

import lab.bank.bankmock.domain.InterbankOutcome
import lab.bank.bankmock.domain.InterbankTransfer
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 외부 기관(bank-mock)의 일자별 정산 파일 형식. **외부 기관이 정한 형식**이라는 입장 (설계 연습 09 결정 19):
 * 우리 추출 파일(CSV)과 일부러 다르게 국내 대외 파일에 흔한 레코드 구분 방식으로 둔다. 받는 쪽(대사 서비스 수신 작업)이 번역한다 (ACL)
 *
 *   H|<처리일 yyyyMMdd>|BANKMOCK                                        헤더
 *   D|<거래키>|<받는 은행>|<받는 계좌>|<금액>|<A=입금 R=거절>|<거절 사유>|<처리 시각 yyyyMMddHHmmss>   건별
 *   T|<건수>|<금액 합계>                                                트레일러 (파일이 다 왔나 확인용)
 *
 * 거래키 = 보내는 쪽이 준 Idempotency-Key (우리 송금 ID). 거절 건도 결과와 함께 싣는다
 */
object SettlementFile {

    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZONE)

    fun write(date: LocalDate, records: List<InterbankTransfer>): String = buildString {
        appendLine("H|${date.format(DATE)}|BANKMOCK")
        for (r in records) {
            val result = if (r.outcome == InterbankOutcome.ACCEPTED) "A" else "R"
            appendLine("D|${r.idempotencyKey}|${r.toBankCode}|${r.toAccountNumber}|${r.amount}|$result|${r.rejectReason ?: ""}|${TIME.format(r.processedAt)}")
        }
        appendLine("T|${records.size}|${records.sumOf { it.amount }}")
    }
}
