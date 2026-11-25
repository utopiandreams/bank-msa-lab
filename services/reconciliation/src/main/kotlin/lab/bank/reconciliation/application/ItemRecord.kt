package lab.bank.reconciliation.application

import lab.bank.reconciliation.domain.CarrySnapshot
import java.time.LocalDate
import java.util.UUID

/** 살아 있는(OPEN·ESCALATED) 예외 항목 */
data class ItemRecord(
    val id: UUID,
    val itemKey: String,
    val kind: String,
    val status: String,
    val snapshot: CarrySnapshot,
    val firstSeenDate: LocalDate,
)
