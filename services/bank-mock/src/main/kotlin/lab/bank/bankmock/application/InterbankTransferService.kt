package lab.bank.bankmock.application

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import lab.bank.bankmock.domain.InterbankOutcome
import lab.bank.bankmock.domain.InterbankTransfer
import lab.bank.bankmock.domain.RejectReason
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * 외부 결제망 흉내: 받은 이체를 성공/거절로 판정하고, 같은 키는 처음 판정을 그대로 돌려준다.
 *
 * 저장은 DB (2026-11-26, 전에는 메모리). 재시작해도 처리 기록·조회·정산 파일이 남는다.
 * 메모리였을 때는 재시작하면 같은 키가 "처음"으로, 조회가 "없음"으로 보여 보내는 쪽이 성공한 이체를 취소할 수 있었고,
 * 정산 파일에서도 빠져 대사가 거짓 불일치(MISSING_EXTERNAL)를 냈다. "외부가 기록을 잃는" 장면은 이제 lookupLiesNotFound 스위치로만 만든다
 *
 * 8주 차 계약 (설계 연습 08 2부 결정 1): **처리 상한 + 조회**.
 *   - 받은 뒤 처리 상한 안에 판정하지 못하면 처리하지 않는다 (기록 없음)
 *   - 조회는 있다(성공·거절)/없다만 답한다. "처리 중"은 없다: 보내는 쪽이 상한이 지난 뒤에만 묻기로 약속했기 때문
 *
 * @param sleeper 지연을 흉내 내는 함수. 테스트에서 실제로 기다리지 않고 호출만 기록하려고 바꿔 끼운다
 * @param meters 지표 (2026-11-26, "은행 운영" 대시보드). 스프링에선 앱의 레지스트리가 주입되고, 테스트에선 기본값(전역, 아무 데도 안 나감)
 *   bankmock_interbank_judged_total{outcome,reason}   새로 판정한 이체 (같은 키 재요청은 안 셈)
 *   bankmock_interbank_deadline_exceeded_total        처리 상한을 넘겨 포기한 이체 (기록 없음 → 보내는 쪽은 결과 불명)
 *   bankmock_interbank_response_delayed_total         기록한 뒤 응답을 늦춘 이체 (보내는 쪽 타임아웃이면 "처리됐는데 모름")
 */
@Service
class InterbankTransferService(
    private val store: InterbankTransferStore,
    private val switches: BankMockSwitches,
    private val random: Random = Random.Default,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it) },
    private val meters: MeterRegistry = Metrics.globalRegistry,
) {
    private val deadlineExceeded = Counter.builder("bankmock.interbank.deadline.exceeded")
        .description("처리 상한을 넘겨 포기한 이체 (기록 없음)").register(meters)
    private val responseDelayed = Counter.builder("bankmock.interbank.response.delayed")
        .description("기록한 뒤 응답을 늦춘 이체").register(meters)

    init {
        // 조합을 미리 0으로 (처음 생기는 순간의 증가가 rate()에 안 잡히는 함정, pitfalls 41)
        judgedCounter(InterbankOutcome.ACCEPTED, null)
        RejectReason.entries.forEach { judgedCounter(InterbankOutcome.REJECTED, it) }
    }

    fun transfer(idempotencyKey: String, toBankCode: String, toAccountNumber: String, amount: Long): InterbankTransfer {
        val settings = switches.current   // 요청 하나는 한 시점의 스위치로 끝까지 (중간에 바뀌어도 섞이지 않게)

        // 이미 판정한 키는 지연 없이 기록을 돌려준다 (재요청이 처리 상한에 걸려 "포기"로 보이면 안 된다: 이미 처리했으므로)
        store.find(idempotencyKey)?.let { return checked(it, toBankCode, toAccountNumber, amount) }

        process(idempotencyKey, settings)

        // 같은 키가 동시에 와서 둘 다 여기까지 왔어도 DB에 남는 판정은 하나 (PK + ON CONFLICT DO NOTHING), 둘 다 그 판정을 받는다
        val judged = judge(idempotencyKey, toBankCode, toAccountNumber, amount, settings)
        val recorded = store.saveIfAbsent(judged)
        if (recorded == judged) judgedCounter(judged.outcome, judged.rejectReason).increment()   // 동시 요청에 진 쪽은 안 센다
        // 기록한 뒤 응답이 늦는 경우: 외부는 처리했는데 보내는 쪽은 타임아웃 = 결과 불명의 가장 위험한 모양
        if (!settings.responseDelay.isZero && hit(settings.responseDelayPercent)) {
            responseDelayed.increment()
            sleeper(settings.responseDelay)
        }
        return checked(recorded, toBankCode, toAccountNumber, amount)
    }

    /**
     * 조회: 기록이 있으면 그 판정, 없으면 null (= 처리 안 함. 처리 상한이 지난 뒤라면 앞으로도 처리하지 않는다).
     * 실험 스위치 lookupLiesNotFound: 외부가 **조회 계약을 어긴다** (처리했는데 조회엔 "없음"). 정산 파일엔 그대로 나온다
     * → 보내는 쪽 해소 일꾼이 출금을 취소해 이중 지급이 나고, 대사만 그걸 잡는다 (ADR-0006 판정표, 8주 차 재현)
     */
    fun find(idempotencyKey: String): InterbankTransfer? =
        if (switches.current.lookupLiesNotFound) null else store.find(idempotencyKey)

    /** 정산 파일용: 그날(Asia/Seoul) 판정한 전부 (성공·거절), 처리 시각 순 */
    fun processedOn(date: LocalDate): List<InterbankTransfer> {
        val from = date.atStartOfDay(SettlementFile.ZONE).toInstant()
        return store.processedBetween(from, date.plusDays(1).atStartOfDay(SettlementFile.ZONE).toInstant())
    }

    // 처리(판정 전) 지연. 상한을 넘으면 상한까지만 기다리고 포기한다: 실제 시스템도 상한에서 끊지, 끝까지 기다렸다 버리지 않는다
    private fun process(idempotencyKey: String, settings: BankMockProperties) {
        val delay = settings.processingDelay
        if (delay.isZero || !hit(settings.processingDelayPercent)) return
        if (delay > settings.processingDeadline) {
            sleeper(settings.processingDeadline)
            deadlineExceeded.increment()
            throw ProcessingDeadlineExceededException(idempotencyKey, settings.processingDeadline)
        }
        sleeper(delay)
    }

    private fun judgedCounter(outcome: InterbankOutcome, reason: RejectReason?): Counter =
        Counter.builder("bankmock.interbank.judged")
            .description("새로 판정한 이체 (같은 키 재요청 제외)")
            .tags("outcome", outcome.name, "reason", reason?.name ?: "none")
            .register(meters)

    // 비율 스위치: 100이면 무작위를 쓰지 않는다 (기존 동작·테스트의 난수 순서를 바꾸지 않게)
    private fun hit(percent: Int): Boolean = percent >= 100 || (percent > 0 && random.nextInt(100) < percent)

    private fun checked(recorded: InterbankTransfer, toBankCode: String, toAccountNumber: String, amount: Long): InterbankTransfer {
        if (!recorded.sameRequestAs(toBankCode, toAccountNumber, amount)) {
            throw IdempotencyConflictException(recorded.idempotencyKey)
        }
        return recorded
    }

    private fun judge(key: String, toBankCode: String, toAccountNumber: String, amount: Long, settings: BankMockProperties): InterbankTransfer {
        val reason = when {
            toAccountNumber.startsWith(settings.rejectAccountPrefix) -> RejectReason.ACCOUNT_NOT_FOUND
            random.nextInt(100) < settings.rejectRatePercent -> RejectReason.RECEIVING_BANK_REJECTED
            else -> null
        }
        return InterbankTransfer(
            idempotencyKey = key,
            toBankCode = toBankCode,
            toAccountNumber = toAccountNumber,
            amount = amount,
            outcome = if (reason == null) InterbankOutcome.ACCEPTED else InterbankOutcome.REJECTED,
            rejectReason = reason,
            // DB(timestamptz)는 마이크로초까지: 맞춰 두면 처음 응답과 재요청(DB에서 읽음)의 기록이 똑같다
            processedAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
        )
    }
}
