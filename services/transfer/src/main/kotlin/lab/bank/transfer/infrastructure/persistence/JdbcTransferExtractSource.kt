package lab.bank.transfer.infrastructure.persistence

import lab.bank.transfer.application.port.out.TransferExtractRow
import lab.bank.transfer.application.port.out.TransferExtractSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * 대사 추출용 읽기. JPA가 아니라 SQL인 이유: 당행·타행 두 엔티티를 합쳐 평평한 줄로 읽는 보고용 쿼리라서.
 *
 * **커서로 흘려 읽는다** (100만 건 대비): PostgreSQL JDBC 드라이버는 기본으로 결과 **전체**를 받아 메모리에 둔다.
 * fetchSize를 주고 **트랜잭션 안(autocommit 끔)**에서 읽어야 서버 커서로 1,000줄씩 가져온다 (둘 중 하나라도 빠지면 조용히 전부 받는다).
 * readOnly: 쓰지 않는다는 표시 + 한 스냅숏에서 일관되게 읽는다. 대가: 읽는 동안 트랜잭션이 열려 있다 (수십만 건이면 수 초~수십 초)
 */
@Component
class JdbcTransferExtractSource(dataSource: DataSource) : TransferExtractSource {

    private val jdbc = JdbcTemplate(dataSource).apply { fetchSize = 1_000 }

    @Transactional(readOnly = true)
    override fun forEachChangedBetween(from: Instant, to: Instant, action: (TransferExtractRow) -> Unit) {
        jdbc.query(
            """
            SELECT t.id, t.kind, t.amount, t.status, t.created_at, t.updated_at,
                   i.to_bank_code, i.to_account_number, i.external_reject_reason
            FROM transfer.transfer t
            LEFT JOIN transfer.interbank_transfer i ON i.transfer_id = t.id
            WHERE (t.created_at >= ? AND t.created_at < ?)
               OR (t.updated_at >= ? AND t.updated_at < ?)
            ORDER BY t.created_at, t.id
            """,
            PreparedStatementSetter { ps ->
                ps.setTimestamp(1, Timestamp.from(from)); ps.setTimestamp(2, Timestamp.from(to))
                ps.setTimestamp(3, Timestamp.from(from)); ps.setTimestamp(4, Timestamp.from(to))
            },
            RowCallbackHandler { rs ->
                action(
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
                    ),
                )
            },
        )
    }
}
