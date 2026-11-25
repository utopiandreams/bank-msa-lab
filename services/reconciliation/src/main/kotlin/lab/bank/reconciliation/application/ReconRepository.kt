package lab.bank.reconciliation.application

import lab.bank.reconciliation.domain.CarrySnapshot
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 대사 서비스 저장 포트 (구현: infrastructure/JdbcReconRepository) */
interface ReconRepository {
    /** 인박스에 넣는다. 이미 받은 추출(같은 extract_id)이면 false (멱등 컨슈머) */
    fun insertArrival(arrival: ExtractArrival): Boolean

    /** (날짜, 출처)의 가장 마지막에 도착한 추출 */
    fun latestArrival(date: LocalDate, source: String): ExtractArrival?

    /** 두 출처의 최신 추출이 다 왔는데 그 조합으로 아직 돌지 않은 날짜, 오래된 순 (이월은 날짜 순서에 기대서) */
    fun pendingDates(): List<LocalDate>

    fun doneRun(date: LocalDate): RunRecord?
    fun latestDoneDate(): LocalDate?

    /** 같은 날짜 대사가 두 곳에서 동시에 돌지 않게 (트랜잭션 끝날 때 풀리는 잠금) */
    fun lockDate(date: LocalDate)

    fun insertRun(run: NewRun)
    fun markSuperseded(runId: UUID)

    /** 다시 돌리기 전에 그 회차가 남긴 흔적을 되돌린다: 그 회차가 만든 항목은 지우고, 풀거나 경보로 올린 항목은 전 상태로 */
    fun undoRun(runId: UUID)

    fun activeItems(pair: String): List<ItemRecord>
    fun insertItem(pair: String, key: String, transferId: UUID?, kind: String, status: String, detail: String, snapshot: CarrySnapshot, date: LocalDate, runId: UUID)
    fun updateItem(id: UUID, kind: String, status: String, detail: String, snapshot: CarrySnapshot, escalatedRunId: UUID?)
    fun resolveItem(id: UUID, date: LocalDate, runId: UUID)
    fun countActive(pair: String, status: String): Int
}

data class NewRun(
    val id: UUID,
    val businessDate: LocalDate,
    val status: String,
    val transferExtractId: UUID,
    val coreExtractId: UUID,
    val logicVersion: Int,
    val transferRows: Long?,
    val coreRows: Long?,
    val carriedIn: Int?,
    val matched: Int?,
    val openItems: Int?,
    val escalatedItems: Int?,
    val trialBalanceOk: Boolean?,
    val failureReason: String?,
    val startedAt: Instant,
    val finishedAt: Instant,
)
