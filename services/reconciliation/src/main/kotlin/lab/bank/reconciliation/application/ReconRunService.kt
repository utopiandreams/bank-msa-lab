package lab.bank.reconciliation.application

import lab.bank.common.event.ExtractCompletedEvent
import lab.bank.reconciliation.domain.CarrySnapshot
import lab.bank.reconciliation.domain.ItemKind
import lab.bank.reconciliation.domain.TransferCoreMatcher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 대사 회차 (ADR-0006). 일꾼이 주기적으로 runPending()을 부른다.
 *
 * 한 날짜 = (transfer 최신 추출, core 최신 추출) 한 조합으로 한 번:
 *   1. 파일 읽기·검증 (트랜잭션 밖. 불량이면 FAILED 회차를 남기고 새 추출을 기다린다)
 *   2. **한 트랜잭션**: 날짜 잠금 → (다시 돌리기면 이전 회차 되돌리기) → 이월 항목 + 오늘 파일 판정 → 항목 반영 → 회차 DONE (결정 17)
 *      죽으면 아무것도 안 남고, 다음 회차가 처음부터 다시 한다 (진행 중 상태를 저장하지 않는다)
 *
 * 다시 돌리기 (같은 날짜에 새 추출이 옴): **가장 최근 대사일만** 허용. 이월은 날짜 순서에 기대서, 지난 날짜를 다시 돌리면
 * 그 뒤 날짜들의 이월이 꼬인다 → 지난 날짜면 FAILED로 남기고 사람이 본다
 */
@Service
class ReconRunService(
    private val repository: ReconRepository,
    private val fileReader: ExtractFileReader,
    private val transactionTemplate: TransactionTemplate,
) {

    fun runPending(): Int {
        var ran = 0
        for (date in repository.pendingDates()) {
            runDate(date)
            ran++
        }
        return ran
    }

    fun runDate(date: LocalDate) {
        val startedAt = Instant.now()
        val transferArrival = repository.latestArrival(date, ExtractCompletedEvent.SOURCE_TRANSFER) ?: return
        val coreArrival = repository.latestArrival(date, ExtractCompletedEvent.SOURCE_CORE) ?: return
        fun failed(reason: String) {
            log.error("대사 회차 실패 date={} reason={}", date, reason)
            repository.insertRun(NewRun(UUID.randomUUID(), date, "FAILED", transferArrival.extractId, coreArrival.extractId, LOGIC_VERSION,
                null, null, null, null, null, null, null, reason.take(500), startedAt, Instant.now()))
        }

        val previous = repository.doneRun(date)
        if (previous != null && repository.latestDoneDate() != date) {
            return failed("지난 날짜는 다시 돌리지 않는다 (가장 최근 대사일 이후 날짜의 이월이 꼬임). 사람이 확인: $date")
        }

        val transfers = try {
            ExtractFiles.parseTransfers(transferArrival, fileReader.read(transferArrival.objectKey))
        } catch (e: Exception) { return failed("transfer 파일 불량: ${e.message}") }
        val entries = try {
            ExtractFiles.parseCoreEntries(coreArrival, fileReader.read(coreArrival.objectKey))
        } catch (e: Exception) { return failed("core 파일 불량: ${e.message}") }

        transactionTemplate.executeWithoutResult {
            repository.lockDate(date)
            previous?.let { repository.undoRun(it.id); repository.markSuperseded(it.id) }
            val runId = UUID.randomUUID()

            val active = repository.activeItems(PAIR).filter { it.kind != ItemKind.TRIAL_BALANCE.name }.associateBy { it.itemKey }
            val evaluations = TransferCoreMatcher.match(transfers, entries, active.mapValues { it.value.snapshot })

            var matched = 0
            for (e in evaluations) {
                val existing = active[e.key]
                when {
                    e.matched && existing != null -> { repository.resolveItem(existing.id, date, runId); matched++ }
                    e.matched -> matched++
                    existing != null -> {
                        // 하루 넘게 안 풀렸거나(결정 14), 바로 경보할 종류면 ESCALATED
                        val escalate = existing.status == "ESCALATED" || e.immediate || existing.firstSeenDate < date
                        if (escalate && existing.status != "ESCALATED") log.error("대사 불일치 경보 date={} key={} kind={} {}", date, e.key, e.kind, e.detail)
                        repository.updateItem(existing.id, e.kind!!.name, if (escalate) "ESCALATED" else "OPEN", e.detail, e.snapshot,
                            if (escalate && existing.status != "ESCALATED") runId else null)
                    }
                    else -> {
                        if (e.immediate) log.error("대사 불일치 경보 date={} key={} kind={} {}", date, e.key, e.kind, e.detail)
                        repository.insertItem(PAIR, e.key, e.transferId, e.kind!!.name, if (e.immediate) "ESCALATED" else "OPEN", e.detail, e.snapshot, date, runId)
                    }
                }
            }

            // 시산표 (결정 1): 그날 원장 차변 합 = 대변 합
            val trialOk = coreArrival.debitSum != null && coreArrival.debitSum == coreArrival.creditSum
            if (!trialOk) {
                val detail = "시산표 불일치 $date: 차변 ${coreArrival.debitSum}, 대변 ${coreArrival.creditSum}"
                log.error("대사 불일치 경보 {}", detail)
                repository.insertItem(PAIR, "trial:$date", null, ItemKind.TRIAL_BALANCE.name, "ESCALATED", detail, CarrySnapshot(null, emptyList()), date, runId)
            }

            repository.insertRun(NewRun(runId, date, "DONE", transferArrival.extractId, coreArrival.extractId, LOGIC_VERSION,
                transfers.size.toLong(), entries.size.toLong(), active.size, matched,
                repository.countActive(PAIR, "OPEN"), repository.countActive(PAIR, "ESCALATED"), trialOk, null, startedAt, Instant.now()))
            log.info("대사 완료 date={} transfer={} core={} 이월받음={} 정상={}", date, transfers.size, entries.size, active.size, matched)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ReconRunService::class.java)
        const val PAIR = "TRANSFER_CORE"
        /** 판정 규칙(TransferCoreMatcher)이 바뀌면 올린다 */
        const val LOGIC_VERSION = 1
    }
}
