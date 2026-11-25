package lab.bank.reconciliation.domain

import java.util.UUID

/**
 * 타행 송금 ↔ 외부 정산 파일 대사의 판정 규칙 (ADR-0006 결정 7 판정표, 개발자 작성). 순수 함수.
 *
 *   우리 끝 상태                         외부 정상값     어긋나면
 *   COMPLETED                            입금(A)         거절 → CUSTOMER_LOSS(즉시) / 없음 → MISSING_EXTERNAL(이월)
 *   CANCELLED (외부 거절 사유 있음)        거절(R)         입금 → DOUBLE_PAYMENT(즉시) / 없음 → MISSING_EXTERNAL(이월)
 *   CANCELLED (사유 없음: 조회 "없음"·안 보냄), FAILED   없음    입금 → DOUBLE_PAYMENT(즉시) / 거절 → MISMATCH(즉시, 계약 위반)
 *   끝 상태 아님                          (판정 안 함)    PENDING (이월)
 *   우리 송금 없음                        —               EXTERNAL_ONLY (이월, 시계 차이)
 * 짝이 있으면 금액·받는 은행·계좌도 맞아야 한다 (MISMATCH, 즉시)
 *
 * 당행 송금은 외부와 상관없어 대상이 아니다 (transfer 파일에서 INTERBANK만)
 */
object TransferExternalMatcher {

    fun match(transfers: List<TransferRow>, external: List<ExternalRow>, carried: Map<String, CarrySnapshot>): List<Evaluation> {
        val todayTransfers = transfers.filter { it.kind == "INTERBANK" }
            .groupBy { it.transferId.toString() }.mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }
        val todayExternal = external.groupBy { it.transferId?.toString() ?: "external:${it.key}" }

        val keys = todayTransfers.keys + todayExternal.keys + carried.keys
        return keys.sorted().map { key ->
            val previous = carried[key]
            val transfer = todayTransfers[key] ?: previous?.transfer
            val rows = (previous?.external.orEmpty() + todayExternal[key].orEmpty()).distinctBy { it.key to it.processedAt }
            evaluate(key, transfer, rows)
        }
    }

    private fun evaluate(key: String, transfer: TransferRow?, rows: List<ExternalRow>): Evaluation {
        val transferId = transfer?.transferId ?: runCatching { UUID.fromString(key) }.getOrNull()
        fun result(kind: ItemKind?, detail: String) = Evaluation(transferId, key, kind, detail, CarrySnapshot(transfer, external = rows))
        val ext = rows.lastOrNull()   // 같은 키는 외부가 한 번만 처리한다 (멱등). 혹시 여럿이면 마지막
        fun describe(r: ExternalRow) = (if (r.accepted) "입금" else "거절(${r.rejectReason})") + " ${r.amount}원 ${r.toBankCode}-${r.toAccountNumber}"

        if (transfer == null) return result(ItemKind.EXTERNAL_ONLY, "우리 송금 기록이 없는 외부 거래: " + rows.joinToString { describe(it) })
        if (!TransferCoreMatcher.isFinal(transfer)) return result(ItemKind.PENDING, "송금이 아직 끝나지 않음: ${transfer.status}")

        if (ext != null && (ext.amount != transfer.amount || ext.toBankCode != transfer.toBankCode || ext.toAccountNumber != transfer.toAccountNumber)) {
            return result(ItemKind.MISMATCH, "값 다름: 우리 ${transfer.amount}원 ${transfer.toBankCode}-${transfer.toAccountNumber}, 외부 " + describe(ext))
        }
        val externallyRejected = transfer.status == "CANCELLED" && transfer.externalRejectReason != null
        return when {
            transfer.status == "COMPLETED" -> when {
                ext == null -> result(ItemKind.MISSING_EXTERNAL, "완료인데 외부 기록 없음")
                ext.accepted -> result(null, "정상")
                else -> result(ItemKind.CUSTOMER_LOSS, "우리는 완료, 외부는 " + describe(ext) + " (고객 돈은 빠졌는데 받는 사람은 못 받음)")
            }
            externallyRejected -> when {
                ext == null -> result(ItemKind.MISSING_EXTERNAL, "외부 거절로 취소했는데 외부 기록 없음")
                !ext.accepted -> result(null, "정상")
                else -> result(ItemKind.DOUBLE_PAYMENT, "우리는 외부 거절로 출금을 돌려줬는데 외부는 " + describe(ext) + " (이중 지급)")
            }
            else -> when {   // CANCELLED(사유 없음) · FAILED: 외부에 없어야 한다
                ext == null -> result(null, "정상")
                ext.accepted -> result(ItemKind.DOUBLE_PAYMENT, "우리는 ${transfer.status}(외부 처리 안 됨으로 봄)인데 외부는 " + describe(ext) + " (이중 지급)")
                else -> result(ItemKind.MISMATCH, "우리는 외부에 처리 기록이 없다고 봤는데 외부는 " + describe(ext) + " (조회 계약 위반)")
            }
        }
    }
}
