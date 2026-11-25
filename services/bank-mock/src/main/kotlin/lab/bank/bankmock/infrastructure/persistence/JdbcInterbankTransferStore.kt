package lab.bank.bankmock.infrastructure.persistence

import lab.bank.bankmock.application.InterbankTransferStore
import lab.bank.bankmock.domain.InterbankOutcome
import lab.bank.bankmock.domain.InterbankTransfer
import lab.bank.bankmock.domain.RejectReason
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcInterbankTransferStore(private val jdbc: JdbcClient) : InterbankTransferStore {

    override fun find(idempotencyKey: String): InterbankTransfer? =
        jdbc.sql("SELECT $COLUMNS FROM bankmock.interbank_transfer WHERE idempotency_key = :key")
            .param("key", idempotencyKey)
            .query { rs, _ -> map(rs) }
            .optional().orElse(null)

    /**
     * ON CONFLICT DO NOTHING: 같은 키가 이미 있으면 아무것도 안 한다 (예외 없음).
     * 다른 트랜잭션이 같은 키를 넣는 중이면 그게 끝날 때까지 기다렸다가 판단한다 → 그 뒤 SELECT엔 먼저 넣은 쪽이 보인다
     */
    override fun saveIfAbsent(candidate: InterbankTransfer): InterbankTransfer {
        val inserted = jdbc.sql(
            """
            INSERT INTO bankmock.interbank_transfer ($COLUMNS)
            VALUES (:key, :bank, :account, :amount, :outcome, :reason, :processedAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
        )
            .param("key", candidate.idempotencyKey)
            .param("bank", candidate.toBankCode)
            .param("account", candidate.toAccountNumber)
            .param("amount", candidate.amount)
            .param("outcome", candidate.outcome.name)
            .param("reason", candidate.rejectReason?.name)
            .param("processedAt", Timestamp.from(candidate.processedAt))
            .update()
        if (inserted == 1) return candidate
        return find(candidate.idempotencyKey) ?: error("키 충돌인데 기록이 없다: ${candidate.idempotencyKey}")
    }

    override fun processedBetween(from: Instant, to: Instant): List<InterbankTransfer> =
        jdbc.sql(
            """
            SELECT $COLUMNS FROM bankmock.interbank_transfer
            WHERE processed_at >= :from AND processed_at < :to
            ORDER BY processed_at, idempotency_key
            """,
        )
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .query { rs, _ -> map(rs) }
            .list()

    private fun map(rs: ResultSet) = InterbankTransfer(
        idempotencyKey = rs.getString("idempotency_key"),
        toBankCode = rs.getString("to_bank_code"),
        toAccountNumber = rs.getString("to_account_number"),
        amount = rs.getLong("amount"),
        outcome = InterbankOutcome.valueOf(rs.getString("outcome")),
        rejectReason = rs.getString("reject_reason")?.let(RejectReason::valueOf),
        processedAt = rs.getTimestamp("processed_at").toInstant(),
    )

    companion object {
        private const val COLUMNS = "idempotency_key, to_bank_code, to_account_number, amount, outcome, reject_reason, processed_at"
    }
}
