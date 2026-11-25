package lab.bank.reconciliation.domain

import java.util.UUID

/**
 * transfer ↔ core 원장 대사의 판정 규칙 (ADR-0006, 설계 연습 09). 순수 함수: 입력(오늘 두 파일 + 이월 항목) → 송금마다 판정.
 *
 * 양방향: 송금에서 출발해 "있어야 할 전표"를 찾고, 전표에서 출발해 "송금이 있나"를 본다.
 * 한쪽에서만 출발하면 송금 기록 없는 전표(core 직접 호출, DB를 다른 시점으로 복구)를 못 찾는다 (결정 2).
 *
 * 판정 대상 = 오늘 송금 줄 ∪ 오늘 전표 ∪ 이월 항목. 같은 송금은 줄을 합친다:
 *   송금 줄은 오늘 것이 있으면 오늘 것(최신 상태), 없으면 이월 스냅숏. 전표는 이월 + 오늘을 합친다 (전표 ID로 중복 제거)
 *
 * 있어야 할 전표 (송금 종류 × 끝 상태):
 *   DOMESTIC  COMPLETED → TRANSFER / FAILED → 없음
 *   INTERBANK COMPLETED → INTERBANK_WITHDRAWAL / CANCELLED → 출금 + 취소(원래 출금을 가리킴) / FAILED → 없음
 *   끝 상태가 아니면 판정하지 않고 PENDING (이월)
 */
object TransferCoreMatcher {

    private val FINAL = mapOf(
        "DOMESTIC" to setOf("COMPLETED", "FAILED"),
        "INTERBANK" to setOf("COMPLETED", "CANCELLED", "FAILED"),
    )

    fun expectedEntryTypes(kind: String, status: String): List<String> = when (kind to status) {
        "DOMESTIC" to "COMPLETED" -> listOf("TRANSFER")
        "INTERBANK" to "COMPLETED" -> listOf("INTERBANK_WITHDRAWAL")
        "INTERBANK" to "CANCELLED" -> listOf("INTERBANK_WITHDRAWAL", "INTERBANK_WITHDRAWAL_CANCEL")
        else -> emptyList()   // FAILED
    }

    fun isFinal(row: TransferRow): Boolean = row.status in FINAL[row.kind].orEmpty()

    fun match(transfers: List<TransferRow>, entries: List<CoreEntryRow>, carried: Map<String, CarrySnapshot>): List<Evaluation> {
        // 같은 송금이 한 파일에 두 번 나올 일은 없지만(만든 날 = 바뀐 날이어도 한 줄), 혹시 몰라 가장 늦게 바뀐 줄을 쓴다
        val todayTransfers = transfers.groupBy { it.transferId.toString() }.mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }
        val todayEntries = entries.groupBy { it.transferId?.toString() ?: "entry:${it.idempotencyKey}" }

        val keys = todayTransfers.keys + todayEntries.keys + carried.keys
        return keys.sorted().map { key ->
            val previous = carried[key]
            val transfer = todayTransfers[key] ?: previous?.transfer
            val allEntries = (previous?.entries.orEmpty() + todayEntries[key].orEmpty()).distinctBy { it.entryId }
            evaluate(key, transfer, allEntries)
        }
    }

    private fun evaluate(key: String, transfer: TransferRow?, entries: List<CoreEntryRow>): Evaluation {
        val transferId = transfer?.transferId ?: runCatching { UUID.fromString(key) }.getOrNull()
        val snapshot = CarrySnapshot(transfer, entries.sortedBy { it.createdAt })
        fun result(kind: ItemKind?, detail: String) = Evaluation(transferId, key, kind, detail, snapshot)

        if (transfer == null) {
            return result(ItemKind.UNEXPECTED_ENTRY, "송금 기록이 없는 전표: " + entries.joinToString { "${it.type} ${it.amount}원 (${it.idempotencyKey})" })
        }
        if (!isFinal(transfer)) {
            return result(ItemKind.PENDING, "송금이 아직 끝나지 않음: ${transfer.kind} ${transfer.status}")
        }

        val expected = expectedEntryTypes(transfer.kind, transfer.status)
        val byType = entries.associateBy { it.type }
        val unexpected = entries.filter { it.type !in expected }
        if (unexpected.isNotEmpty()) {
            return result(ItemKind.UNEXPECTED_ENTRY, "${transfer.kind} ${transfer.status}인데 있으면 안 되는 전표: " + unexpected.joinToString { "${it.type} ${it.amount}원" })
        }
        val wrongAmount = entries.filter { it.amount != transfer.amount }
        if (wrongAmount.isNotEmpty()) {
            return result(ItemKind.MISMATCH, "금액 다름: 송금 ${transfer.amount}원, 전표 " + wrongAmount.joinToString { "${it.type} ${it.amount}원" })
        }
        val cancel = byType["INTERBANK_WITHDRAWAL_CANCEL"]
        val withdrawal = byType["INTERBANK_WITHDRAWAL"]
        if (cancel != null && withdrawal != null && cancel.reversesEntryId != withdrawal.entryId) {
            return result(ItemKind.MISMATCH, "취소 전표가 다른 출금을 가리킴: ${cancel.reversesEntryId} (출금 ${withdrawal.entryId})")
        }
        val missing = expected.filter { it !in byType }
        if (missing.isNotEmpty()) {
            return result(ItemKind.MISSING_ENTRY, "${transfer.kind} ${transfer.status}인데 전표 없음: " + missing.joinToString())
        }
        return result(null, "정상")
    }
}
