package lab.bank.reconciliation.application

import java.time.LocalDate
import java.util.UUID

data class RunRecord(val id: UUID, val businessDate: LocalDate, val status: String, val transferExtractId: UUID, val coreExtractId: UUID)
