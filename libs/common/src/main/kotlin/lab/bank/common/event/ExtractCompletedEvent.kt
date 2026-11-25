package lab.bank.common.event

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 일자별 대사 추출 파일을 저장소(Garage, S3 호환)에 **다 올린 뒤** 발행한다 (ADR-0006). transfer·core가 발행, reconciliation이 구독(인박스).
 *
 * 파일이 이벤트보다 먼저다: 이벤트를 받았는데 파일이 없으면 안 된다 (설계 연습 09 결정 11).
 * rowCount·amountSum·sha256은 정산 파일의 trailer 역할: 대사 서비스가 받은 파일과 대조해 "다 왔나, 깨지지 않았나"를 확인한다 (결정 12).
 * 같은 날짜를 다시 추출하면 extractId·objectKey가 새로 생긴다 (저장소가 덮어쓰기를 못 막아서 앱이 키를 매번 새로 만든다).
 * 대사 서비스는 (source, businessDate)마다 **가장 마지막에 도착한** 추출을 쓴다.
 *
 * 계약은 클래스가 아니라 JSON 모양 (TransferFinishedEvent 주석과 같은 규칙: 추가는 괜찮고, 지우거나 바꾸면 schemaVersion)
 */
data class ExtractCompletedEvent(
    val eventId: UUID,
    val schemaVersion: Int,
    val source: String,              // SOURCE_TRANSFER | SOURCE_CORE
    val businessDate: LocalDate,     // 영업일 (BUSINESS_ZONE 기준 하루)
    val extractId: UUID,
    val objectKey: String,           // <source>/<날짜>/<extractId>.csv
    val rowCount: Long,              // 헤더 제외 줄 수
    val amountSum: Long,             // amount 칸 합계 (원)
    val sha256: String,              // 파일 바이트의 SHA-256 (hex)
    val extractedAt: Instant,
    // core만: 그날 원장 전체(모든 전표 종류)의 차변 합·대변 합. 대사 서비스가 같은지 본다 (시산표, ADR-0006 결정 1)
    val debitSum: Long? = null,
    val creditSum: Long? = null,
) {
    companion object {
        const val TOPIC = "reconciliation-extracts"
        const val SCHEMA_VERSION = 1
        const val SOURCE_TRANSFER = "transfer"
        const val SOURCE_CORE = "core"

        /** 영업일 기준 시간대. 송금 createdAt(Instant)을 이 시간대의 날짜로 자른다 */
        val BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        fun objectKey(source: String, businessDate: LocalDate, extractId: UUID) = "$source/$businessDate/$extractId.csv"
    }
}
