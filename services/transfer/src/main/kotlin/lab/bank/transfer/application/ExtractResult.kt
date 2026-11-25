package lab.bank.transfer.application

import java.util.UUID

data class ExtractResult(
    val extractId: UUID,
    val objectKey: String,
    val rowCount: Long,
    val amountSum: Long,
)
