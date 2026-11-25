package lab.bank.reconciliation.domain

/**
 * 이월 항목이 다음 날 다시 판정되려고 들고 가는 원본: 마지막으로 본 송금 줄 + 지금까지 본 전표들.
 * 다음 날 파일에 같은 송금(상태가 바뀌어 다시 실림)이나 전표가 나오면 합쳐서 다시 판정한다
 */
data class CarrySnapshot(
    val transfer: TransferRow?,
    val entries: List<CoreEntryRow>,
)
