package lab.bank.reconciliation.domain

import java.util.UUID

/**
 * 송금 하나(또는 송금 없는 전표 하나)를 판정한 결과.
 * kind == null 이면 정상. immediate = 이월 없이 바로 경보할 종류인가 (결정 7·14)
 */
data class Evaluation(
    val transferId: UUID?,
    val key: String,                    // 항목 식별: 송금 ID 문자열, 송금 없는 전표는 "entry:<전표 키>"
    val kind: ItemKind?,
    val detail: String,
    val snapshot: CarrySnapshot,        // 이월할 때 다음 날 다시 판정하려고 남기는 원본 줄들
) {
    val matched: Boolean get() = kind == null
    val immediate: Boolean get() = kind?.immediate == true
}
