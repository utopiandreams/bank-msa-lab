package lab.bank.core.extract.application

import lab.bank.core.ledger.application.EntryExtractRow

/** core 추출 파일 형식 (설계 연습 09 결정 20, transfer TransferExtractCsv와 같은 규칙). 칸에 쉼표가 들어갈 일이 없어 따옴표 처리는 필요 없다 */
object CoreExtractCsv {

    val HEADER = listOf("entry_id", "idempotency_key", "type", "amount", "created_at", "reverses_entry_id")

    fun write(rows: List<EntryExtractRow>): String = buildString {
        appendLine(HEADER.joinToString(","))
        for (r in rows) {
            appendLine(listOf(r.entryId, r.idempotencyKey, r.type, r.amount, r.createdAt, r.reversesEntryId ?: "").joinToString(","))
        }
    }
}
