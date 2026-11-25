package lab.bank.core.extract.application

import lab.bank.core.ledger.application.EntryExtractRow

/** core 추출 파일 형식 (설계 연습 09 결정 20, transfer TransferExtractCsv와 같은 규칙). 줄 단위로 만든다 (100만 건 대비). 칸에 쉼표가 들어갈 일이 없다 */
object CoreExtractCsv {

    const val HEADER = "entry_id,idempotency_key,type,amount,created_at,reverses_entry_id"

    fun line(r: EntryExtractRow): String =
        listOf(r.entryId, r.idempotencyKey, r.type, r.amount, r.createdAt, r.reversesEntryId ?: "").joinToString(",")
}
