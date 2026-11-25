package lab.bank.reconciliation.application

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.reconciliation.domain.CarrySnapshot
import lab.bank.reconciliation.domain.Evaluation
import lab.bank.reconciliation.domain.ItemKind
import lab.bank.reconciliation.domain.TransferCoreMatcher
import lab.bank.reconciliation.domain.TransferExternalMatcher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 대사 회차 (ADR-0006). 일꾼이 주기적으로 runPending()을 부른다. 쌍 두 개를 따로 돈다:
 *   TRANSFER_CORE     = transfer 추출 ↔ core 추출 (+ 시산표)
 *   TRANSFER_EXTERNAL = transfer 추출(타행만) ↔ 외부 정산 파일 (결정 7 판정표)
 *
 * 한 (쌍, 날짜) = (transfer 최신 추출, 상대 최신 추출) 한 조합으로 한 번:
 *   1. 파일 읽기·검증 (트랜잭션 밖. 불량이면 FAILED 회차를 남기고 새 추출을 기다린다)
 *   2. **한 트랜잭션**: 잠금 → (다시 돌리기면 이전 회차 되돌리기) → 이월 항목 + 오늘 파일 판정 → 항목 반영 → 회차 DONE (결정 17)
 *      죽으면 아무것도 안 남고, 다음 회차가 처음부터 다시 한다 (진행 중 상태를 저장하지 않는다)
 *
 * 다시 돌리기 (같은 날짜에 새 추출이 옴): 그 쌍의 **가장 최근 대사일만** 허용. 이월은 날짜 순서에 기대서
 */
@Service
class ReconRunService(
    private val repository: ReconRepository,
    private val fileReader: ExtractFileReader,
    private val transactionTemplate: TransactionTemplate,
) {

    fun runPending(): Int {
        var ran = 0
        for (date in repository.pendingDates(PAIR_CORE, ExtractCompletedEvent.SOURCE_CORE)) { runDate(PAIR_CORE, date); ran++ }
        for (date in repository.pendingDates(PAIR_EXTERNAL, ExternalFileReceiver.SOURCE)) { runDate(PAIR_EXTERNAL, date); ran++ }
        return ran
    }

    fun runDate(date: LocalDate) = runDate(PAIR_CORE, date)

    fun runDate(pair: String, date: LocalDate) {
        val startedAt = Instant.now()
        val otherSource = if (pair == PAIR_CORE) ExtractCompletedEvent.SOURCE_CORE else ExternalFileReceiver.SOURCE
        val transferArrival = repository.latestArrival(date, ExtractCompletedEvent.SOURCE_TRANSFER) ?: return
        val otherArrival = repository.latestArrival(date, otherSource) ?: return
        fun run(id: UUID, status: String, reason: String?, counts: Counts?) = NewRun(
            id, pair, date, status, transferArrival.extractId,
            otherArrival.extractId.takeIf { pair == PAIR_CORE }, otherArrival.extractId.takeIf { pair == PAIR_EXTERNAL },
            LOGIC_VERSION, counts?.transferRows, counts?.otherRows, counts?.carriedIn, counts?.matched, counts?.open, counts?.escalated,
            counts?.trialOk, reason?.take(500), startedAt, Instant.now(),
        )
        fun failed(reason: String) {
            log.error("대사 회차 실패 pair={} date={} reason={}", pair, date, reason)
            repository.insertRun(run(UUID.randomUUID(), "FAILED", reason, null))
        }

        val previous = repository.doneRun(pair, date)
        if (previous != null && repository.latestDoneDate(pair) != date) {
            return failed("지난 날짜는 다시 돌리지 않는다 (가장 최근 대사일 이후 날짜의 이월이 꼬임). 사람이 확인: $date")
        }

        val transfers = try {
            ExtractFiles.parseTransfers(transferArrival, fileReader.read(transferArrival.objectKey))
        } catch (e: Exception) { return failed("transfer 파일 불량: ${e.message}") }
        val other: Any = try {
            val bytes = fileReader.read(otherArrival.objectKey)
            if (pair == PAIR_CORE) ExtractFiles.parseCoreEntries(otherArrival, bytes) else ExtractFiles.parseExternal(otherArrival, bytes)
        } catch (e: Exception) { return failed("$otherSource 파일 불량: ${e.message}") }

        transactionTemplate.executeWithoutResult {
            repository.lockDate(pair, date)
            previous?.let { repository.undoRun(it.id); repository.markSuperseded(it.id) }
            val runId = UUID.randomUUID()

            val active = repository.activeItems(pair).filter { it.kind != ItemKind.TRIAL_BALANCE.name }.associateBy { it.itemKey }
            val carried = active.mapValues { it.value.snapshot }
            @Suppress("UNCHECKED_CAST")
            val evaluations: List<Evaluation> =
                if (pair == PAIR_CORE) TransferCoreMatcher.match(transfers, other as List<lab.bank.reconciliation.domain.CoreEntryRow>, carried)
                else TransferExternalMatcher.match(transfers, other as List<lab.bank.reconciliation.domain.ExternalRow>, carried)

            var matched = 0
            for (e in evaluations) {
                val existing = active[e.key]
                when {
                    e.matched && existing != null -> { repository.resolveItem(existing.id, date, runId); matched++ }
                    e.matched -> matched++
                    existing != null -> {
                        // 하루 넘게 안 풀렸거나(결정 14), 바로 경보할 종류면 ESCALATED
                        val escalate = existing.status == "ESCALATED" || e.immediate || existing.firstSeenDate < date
                        val newlyEscalated = escalate && existing.status != "ESCALATED"
                        if (newlyEscalated) alert(pair, date, e)
                        repository.updateItem(existing.id, e.kind!!.name, if (escalate) "ESCALATED" else "OPEN", e.detail, e.snapshot, if (newlyEscalated) runId else null)
                    }
                    else -> {
                        if (e.immediate) alert(pair, date, e)
                        repository.insertItem(pair, e.key, e.transferId, e.kind!!.name, if (e.immediate) "ESCALATED" else "OPEN", e.detail, e.snapshot, date, runId)
                    }
                }
            }

            // 시산표 (결정 1): 그날 원장 차변 합 = 대변 합. core 쌍에서만
            var trialOk: Boolean? = null
            if (pair == PAIR_CORE) {
                trialOk = otherArrival.debitSum != null && otherArrival.debitSum == otherArrival.creditSum
                if (!trialOk) {
                    val detail = "시산표 불일치 $date: 차변 ${otherArrival.debitSum}, 대변 ${otherArrival.creditSum}"
                    log.error("대사 불일치 경보 {}", detail)
                    repository.insertItem(pair, "trial:$date", null, ItemKind.TRIAL_BALANCE.name, "ESCALATED", detail, CarrySnapshot(null), date, runId)
                }
            }

            val otherRows = (other as List<*>).size.toLong()
            repository.insertRun(run(runId, "DONE", null, Counts(transfers.size.toLong(), otherRows, active.size, matched,
                repository.countActive(pair, "OPEN"), repository.countActive(pair, "ESCALATED"), trialOk)))
            log.info("대사 완료 pair={} date={} transfer={} 상대={} 이월받음={} 정상={}", pair, date, transfers.size, otherRows, active.size, matched)
        }
    }

    private fun alert(pair: String, date: LocalDate, e: Evaluation) =
        log.error("대사 불일치 경보 pair={} date={} key={} kind={} {}", pair, date, e.key, e.kind, e.detail)

    private data class Counts(val transferRows: Long, val otherRows: Long, val carriedIn: Int, val matched: Int, val open: Int, val escalated: Int, val trialOk: Boolean?)

    companion object {
        private val log = LoggerFactory.getLogger(ReconRunService::class.java)
        const val PAIR = "TRANSFER_CORE"
        const val PAIR_CORE = "TRANSFER_CORE"
        const val PAIR_EXTERNAL = "TRANSFER_EXTERNAL"
        /** 판정 규칙(TransferCoreMatcher·TransferExternalMatcher)이 바뀌면 올린다 */
        const val LOGIC_VERSION = 1
    }
}
