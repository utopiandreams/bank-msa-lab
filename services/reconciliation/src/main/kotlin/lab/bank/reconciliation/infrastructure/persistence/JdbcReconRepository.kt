package lab.bank.reconciliation.infrastructure.persistence

import lab.bank.reconciliation.application.ExtractArrival
import lab.bank.reconciliation.application.ItemRecord
import lab.bank.reconciliation.application.NewRun
import lab.bank.reconciliation.application.ReconRepository
import lab.bank.reconciliation.application.RunRecord
import lab.bank.reconciliation.domain.CarrySnapshot
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 대사 저장 (JdbcClient). JPA가 아닌 이유: 인박스의 ON CONFLICT DO NOTHING, DISTINCT ON, 부분 유니크 인덱스, jsonb처럼
 * PostgreSQL 기능을 그대로 쓰는 보고·배치용 테이블이라 SQL이 더 읽기 쉽다
 */
@Component
class JdbcReconRepository(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
) : ReconRepository {

    override fun insertArrival(arrival: ExtractArrival): Boolean =
        jdbc.sql(
            """
            INSERT INTO reconciliation.extract_arrival
              (extract_id, source, business_date, object_key, row_count, amount_sum, sha256, debit_sum, credit_sum, extracted_at, received_at)
            VALUES (:id, :source, :date, :key, :rows, :sum, :sha, :debit, :credit, :extractedAt, :receivedAt)
            ON CONFLICT (extract_id) DO NOTHING
            """,
        )
            .param("id", arrival.extractId).param("source", arrival.source).param("date", Date.valueOf(arrival.businessDate))
            .param("key", arrival.objectKey).param("rows", arrival.rowCount).param("sum", arrival.amountSum).param("sha", arrival.sha256)
            .param("debit", arrival.debitSum).param("credit", arrival.creditSum)
            .param("extractedAt", Timestamp.from(arrival.extractedAt)).param("receivedAt", Timestamp.from(arrival.receivedAt))
            .update() == 1

    override fun latestArrival(date: LocalDate, source: String): ExtractArrival? =
        jdbc.sql("SELECT * FROM reconciliation.extract_arrival WHERE business_date = :date AND source = :source ORDER BY received_at DESC LIMIT 1")
            .param("date", Date.valueOf(date)).param("source", source)
            .query { rs, _ -> arrival(rs) }.optional().orElse(null)

    override fun pendingDates(pair: String, otherSource: String): List<LocalDate> =
        jdbc.sql(
            """
            WITH latest AS (
              SELECT DISTINCT ON (business_date, source) business_date, source, extract_id
              FROM reconciliation.extract_arrival
              ORDER BY business_date, source, received_at DESC
            )
            SELECT t.business_date
            FROM latest t JOIN latest o ON o.business_date = t.business_date AND o.source = :other
            WHERE t.source = 'transfer'
              AND NOT EXISTS (
                SELECT 1 FROM reconciliation.recon_run r
                WHERE r.pair = :pair AND r.business_date = t.business_date AND r.transfer_extract_id = t.extract_id
                  AND coalesce(r.core_extract_id, r.external_extract_id) = o.extract_id
              )
            ORDER BY t.business_date
            """,
        ).param("pair", pair).param("other", otherSource).query { rs, _ -> rs.getDate(1).toLocalDate() }.list()

    override fun doneRun(pair: String, date: LocalDate): RunRecord? =
        jdbc.sql("SELECT id, pair, business_date, status FROM reconciliation.recon_run WHERE pair = :pair AND business_date = :date AND status = 'DONE'")
            .param("pair", pair).param("date", Date.valueOf(date))
            .query { rs, _ -> RunRecord(rs.getObject("id", UUID::class.java), rs.getString("pair"), rs.getDate("business_date").toLocalDate(), rs.getString("status")) }
            .optional().orElse(null)

    override fun latestDoneDate(pair: String): LocalDate? =
        jdbc.sql("SELECT max(business_date) FROM reconciliation.recon_run WHERE pair = :pair AND status = 'DONE'")
            .param("pair", pair)
            .query { rs, _ -> rs.getDate(1)?.toLocalDate() }.single()

    override fun lockDate(pair: String, date: LocalDate) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:p, :k)").param("p", pair.hashCode()).param("k", date.toEpochDay().toInt()).query { _, _ -> 1 }.list()
    }

    override fun insertRun(run: NewRun) {
        jdbc.sql(
            """
            INSERT INTO reconciliation.recon_run
              (id, pair, business_date, status, transfer_extract_id, core_extract_id, external_extract_id, logic_version, transfer_rows, core_rows, carried_in, matched,
               open_items, escalated_items, trial_balance_ok, failure_reason, started_at, finished_at)
            VALUES (:id, :pair, :date, :status, :t, :c, :x, :v, :tr, :cr, :ci, :m, :o, :e, :tb, :fr, :sa, :fa)
            """,
        )
            .param("id", run.id).param("date", Date.valueOf(run.businessDate)).param("status", run.status)
            .param("pair", run.pair).param("t", run.transferExtractId).param("c", run.coreExtractId).param("x", run.externalExtractId).param("v", run.logicVersion)
            .param("tr", run.transferRows).param("cr", run.coreRows).param("ci", run.carriedIn).param("m", run.matched)
            .param("o", run.openItems).param("e", run.escalatedItems).param("tb", run.trialBalanceOk).param("fr", run.failureReason)
            .param("sa", Timestamp.from(run.startedAt)).param("fa", Timestamp.from(run.finishedAt))
            .update()
    }

    override fun markSuperseded(runId: UUID) {
        jdbc.sql("UPDATE reconciliation.recon_run SET status = 'SUPERSEDED' WHERE id = :id").param("id", runId).update()
    }

    override fun undoRun(runId: UUID) {
        // 그 회차가 처음 만든 항목은 지운다
        jdbc.sql("DELETE FROM reconciliation.recon_item WHERE first_seen_run_id = :id").param("id", runId).update()
        // 그 회차가 푼 항목은 다시 살린다 (그 전에 경보였으면 경보로)
        jdbc.sql(
            """
            UPDATE reconciliation.recon_item
            SET status = CASE WHEN escalated_run_id IS NOT NULL AND escalated_run_id <> :id THEN 'ESCALATED' ELSE 'OPEN' END,
                resolved_run_id = NULL, resolved_date = NULL, updated_at = now()
            WHERE resolved_run_id = :id
            """,
        ).param("id", runId).update()
        // 그 회차가 경보로 올린 항목은 이월로 되돌린다
        jdbc.sql("UPDATE reconciliation.recon_item SET status = 'OPEN', escalated_run_id = NULL, updated_at = now() WHERE escalated_run_id = :id AND status = 'ESCALATED'")
            .param("id", runId).update()
    }

    override fun activeItems(pair: String): List<ItemRecord> =
        jdbc.sql("SELECT id, item_key, kind, status, snapshot::text AS snapshot, first_seen_date FROM reconciliation.recon_item WHERE pair = :pair AND status IN ('OPEN', 'ESCALATED')")
            .param("pair", pair)
            .query { rs, _ ->
                ItemRecord(rs.getObject("id", UUID::class.java), rs.getString("item_key"), rs.getString("kind"), rs.getString("status"),
                    jsonMapper.readValue(rs.getString("snapshot"), CarrySnapshot::class.java), rs.getDate("first_seen_date").toLocalDate())
            }.list()

    override fun insertItem(pair: String, key: String, transferId: UUID?, kind: String, status: String, detail: String, snapshot: CarrySnapshot, date: LocalDate, runId: UUID) {
        jdbc.sql(
            """
            INSERT INTO reconciliation.recon_item
              (id, pair, item_key, transfer_id, kind, status, detail, snapshot, first_seen_date, first_seen_run_id, escalated_run_id, updated_at)
            VALUES (:id, :pair, :key, :tid, :kind, :status, :detail, CAST(:snapshot AS jsonb), :date, :run, :esc, now())
            """,
        )
            .param("id", UUID.randomUUID()).param("pair", pair).param("key", key).param("tid", transferId).param("kind", kind)
            .param("status", status).param("detail", detail.take(1000)).param("snapshot", jsonMapper.writeValueAsString(snapshot))
            .param("date", Date.valueOf(date)).param("run", runId).param("esc", if (status == "ESCALATED") runId else null)
            .update()
    }

    override fun updateItem(id: UUID, kind: String, status: String, detail: String, snapshot: CarrySnapshot, escalatedRunId: UUID?) {
        jdbc.sql(
            """
            UPDATE reconciliation.recon_item
            SET kind = :kind, status = :status, detail = :detail, snapshot = CAST(:snapshot AS jsonb),
                escalated_run_id = coalesce(:esc, escalated_run_id), updated_at = now()
            WHERE id = :id
            """,
        )
            .param("id", id).param("kind", kind).param("status", status).param("detail", detail.take(1000))
            .param("snapshot", jsonMapper.writeValueAsString(snapshot)).param("esc", escalatedRunId)
            .update()
    }

    override fun resolveItem(id: UUID, date: LocalDate, runId: UUID) {
        jdbc.sql("UPDATE reconciliation.recon_item SET status = 'RESOLVED', resolved_run_id = :run, resolved_date = :date, updated_at = now() WHERE id = :id")
            .param("id", id).param("run", runId).param("date", Date.valueOf(date)).update()
    }

    override fun countActive(pair: String, status: String): Int =
        jdbc.sql("SELECT count(*) FROM reconciliation.recon_item WHERE pair = :pair AND status = :status")
            .param("pair", pair).param("status", status).query(Int::class.java).single()

    private fun arrival(rs: ResultSet) = ExtractArrival(
        extractId = rs.getObject("extract_id", UUID::class.java),
        source = rs.getString("source"),
        businessDate = rs.getDate("business_date").toLocalDate(),
        objectKey = rs.getString("object_key"),
        rowCount = rs.getLong("row_count"),
        amountSum = rs.getLong("amount_sum"),
        sha256 = rs.getString("sha256"),
        debitSum = rs.getObject("debit_sum") as Long?,
        creditSum = rs.getObject("credit_sum") as Long?,
        extractedAt = rs.getTimestamp("extracted_at").toInstant(),
        receivedAt = rs.getTimestamp("received_at").toInstant(),
    )
}
