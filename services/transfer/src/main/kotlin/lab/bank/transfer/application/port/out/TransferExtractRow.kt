package lab.bank.transfer.application.port.out

import java.time.Instant
import java.util.UUID

/**
 * 대사 추출 파일 한 줄 = 송금 하나 (당행·타행 공통, 설계 연습 09 결정 20). 도메인 객체가 아니라 내보내기용 평평한 모양.
 * 짝 맞추는 키는 송금 ID (core 전표 키·외부 송금 키가 다 송금 ID). 고객 멱등 키는 내보내지 않는다 (대사에 쓸 일이 없다)
 */
data class TransferExtractRow(
    val transferId: UUID,
    val kind: String,                     // DOMESTIC | INTERBANK
    val amount: Long,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val toBankCode: String?,              // 타행만
    val toAccountNumber: String?,         // 타행만
    val externalRejectReason: String?,    // 타행 CANCELLED 중 외부 거절만 (판정표: 외부 정상값 REJECTED)
)
