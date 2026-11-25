package lab.bank.reconciliation.domain

/**
 * 이월 항목이 다음 날 다시 판정되려고 들고 가는 원본: 마지막으로 본 송금 줄 + 지금까지 본 상대 쪽 줄들.
 * 다음 날 파일에 같은 송금(상태가 바뀌어 다시 실림)이나 상대 줄이 나오면 합쳐서 다시 판정한다.
 * entries는 transfer ↔ core 쌍, external은 transfer ↔ 외부 쌍에서 쓴다 (기본값 = 옛 스냅숏 JSON도 읽히게)
 */
data class CarrySnapshot(
    val transfer: TransferRow?,
    val entries: List<CoreEntryRow> = emptyList(),
    val external: List<ExternalRow> = emptyList(),
)
