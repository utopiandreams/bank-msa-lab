package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.application.port.out.TransferExtractRow
import lab.bank.transfer.application.port.out.TransferExtractSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * 대사 추출용 읽기. JPA가 아니라 SQL인 이유: 당행·타행 두 엔티티를 합쳐 평평한 줄로 한 번에 읽는 보고용 쿼리라서
 * (엔티티는 kind별 @SQLRestriction으로 나뉘어 있다). 하루치 수천 건 규모라 한 번에 읽는다. 수십만 건이면 커서로 흘려 쓴다
 */
@Component
class JdbcTransferExtractSource(
    private val jdbcClient: JdbcClient,
) : TransferExtractSource {

    override fun rowsChangedBetween(from: Instant, to: Instant): List<TransferExtractRow> =
        jdbcClient.sql(
            """
            SELECT t.id, t.kind, t.amount, t.status, t.created_at, t.updated_at,
                   i.to_bank_code, i.to_account_number, i.external_reject_reason
            FROM transfer.transfer t
            LEFT JOIN transfer.interbank_transfer i ON i.transfer_id = t.id
            WHERE (t.created_at >= :from AND t.created_at < :to)
               OR (t.updated_at >= :from AND t.updated_at < :to)
            ORDER BY t.created_at, t.id
            """,
        )
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .query { rs, _ ->
                TransferExtractRow(
                    transferId = rs.getObject("id", UUID::class.java),
                    kind = rs.getString("kind"),
                    amount = rs.getLong("amount"),
                    status = rs.getString("status"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                    toBankCode = rs.getString("to_bank_code"),
                    toAccountNumber = rs.getString("to_account_number"),
                    externalRejectReason = rs.getString("external_reject_reason"),
                )
            }
            .list()
}
