package lab.bank.reconciliation.application

import java.time.LocalDate
import java.util.UUID

data class RunRecord(val id: UUID, val pair: String, val businessDate: LocalDate, val status: String)
