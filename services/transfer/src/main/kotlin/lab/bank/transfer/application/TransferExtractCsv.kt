package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.TransferExtractRow

/**
 * 대사 추출 파일 형식 (설계 연습 09 결정 20): 헤더 있음, UTF-8, 쉼표, RFC 4180 따옴표, 시각 ISO-8601 UTC, 금액 원 정수, 없음 = 빈 칸.
 * 형식이 곧 대사 서비스와의 계약이라 application에 둔다 (저장 기술이 아니라 "무엇을 내보내나"의 일부).
 * 지금 칸은 UUID·코드·숫자·시각뿐이지만 계좌번호는 자유 문자열이라 따옴표 규칙을 지킨다
 */
object TransferExtractCsv {

    val HEADER = listOf(
        "transfer_id", "kind", "amount", "status", "created_at", "updated_at",
        "to_bank_code", "to_account_number", "external_reject_reason",
    )

    fun write(rows: List<TransferExtractRow>): String = buildString {
        appendLine(HEADER.joinToString(","))
        for (r in rows) {
            appendLine(
                listOf(
                    r.transferId.toString(), r.kind, r.amount.toString(), r.status,
                    r.createdAt.toString(), r.updatedAt.toString(),
                    r.toBankCode, r.toAccountNumber, r.externalRejectReason,
                ).joinToString(",") { escape(it) },
            )
        }
    }

    // RFC 4180: 쉼표·큰따옴표·줄바꿈이 있으면 큰따옴표로 감싸고, 안의 큰따옴표는 두 개로
    private fun escape(value: String?): String {
        if (value == null) return ""
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
