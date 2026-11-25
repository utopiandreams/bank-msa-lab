package lab.bank.bankmock.application

import lab.bank.bankmock.domain.InterbankOutcome
import lab.bank.bankmock.domain.InterbankTransfer
import lab.bank.bankmock.domain.RejectReason
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 외부 결제망 흉내: 받은 이체를 성공/거절로 판정하고, 같은 키는 처음 판정을 그대로 돌려준다.
 *
 * 저장은 메모리. 남의 은행이라 우리가 내구성을 책임질 대상이 아니다 (ADR-0004).
 * 한계: 재시작하면 기록이 사라져 같은 키가 "처음"으로, 조회가 "없음"으로 보인다.
 * 그러면 보내는 쪽이 성공한 이체를 취소할 수 있다 (이중 지급) → 가짜의 한계로 두고 대사가 잡는다 (설계 연습 08 2부 결정 6)
 *
 * 8주 차 계약 (설계 연습 08 2부 결정 1): **처리 상한 + 조회**.
 *   - 받은 뒤 처리 상한 안에 판정하지 못하면 처리하지 않는다 (기록 없음)
 *   - 조회는 있다(성공·거절)/없다만 답한다. "처리 중"은 없다: 보내는 쪽이 상한이 지난 뒤에만 묻기로 약속했기 때문
 *
 * @param sleeper 지연을 흉내 내는 함수. 테스트에서 실제로 기다리지 않고 호출만 기록하려고 바꿔 끼운다
 */
@Service
class InterbankTransferService(
    private val properties: BankMockProperties,
    private val random: Random = Random.Default,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it) },
) {
    private val transfers = ConcurrentHashMap<String, InterbankTransfer>()

    fun transfer(idempotencyKey: String, toBankCode: String, toAccountNumber: String, amount: Long): InterbankTransfer {
        // 이미 판정한 키는 지연 없이 기록을 돌려준다 (재요청이 처리 상한에 걸려 "포기"로 보이면 안 된다: 이미 처리했으므로)
        transfers[idempotencyKey]?.let { return checked(it, toBankCode, toAccountNumber, amount) }

        process(idempotencyKey)

        // computeIfAbsent: 같은 키가 동시에 와도 판정은 한 번만 일어나고 둘 다 같은 결과를 받는다
        val recorded = transfers.computeIfAbsent(idempotencyKey) {
            judge(idempotencyKey, toBankCode, toAccountNumber, amount)
        }
        // 기록한 뒤 응답이 늦는 경우: 외부는 처리했는데 보내는 쪽은 타임아웃 = 결과 불명의 가장 위험한 모양
        if (!properties.responseDelay.isZero) sleeper(properties.responseDelay)
        return checked(recorded, toBankCode, toAccountNumber, amount)
    }

    /** 조회: 기록이 있으면 그 판정, 없으면 null (= 처리 안 함. 처리 상한이 지난 뒤라면 앞으로도 처리하지 않는다) */
    fun find(idempotencyKey: String): InterbankTransfer? = transfers[idempotencyKey]

    // 처리(판정 전) 지연. 상한을 넘으면 상한까지만 기다리고 포기한다: 실제 시스템도 상한에서 끊지, 끝까지 기다렸다 버리지 않는다
    private fun process(idempotencyKey: String) {
        val delay = properties.processingDelay
        if (delay > properties.processingDeadline) {
            sleeper(properties.processingDeadline)
            throw ProcessingDeadlineExceededException(idempotencyKey, properties.processingDeadline)
        }
        if (!delay.isZero) sleeper(delay)
    }

    private fun checked(recorded: InterbankTransfer, toBankCode: String, toAccountNumber: String, amount: Long): InterbankTransfer {
        if (!recorded.sameRequestAs(toBankCode, toAccountNumber, amount)) {
            throw IdempotencyConflictException(recorded.idempotencyKey)
        }
        return recorded
    }

    private fun judge(key: String, toBankCode: String, toAccountNumber: String, amount: Long): InterbankTransfer {
        val reason = when {
            toAccountNumber.startsWith(properties.rejectAccountPrefix) -> RejectReason.ACCOUNT_NOT_FOUND
            random.nextInt(100) < properties.rejectRatePercent -> RejectReason.RECEIVING_BANK_REJECTED
            else -> null
        }
        return InterbankTransfer(
            idempotencyKey = key,
            toBankCode = toBankCode,
            toAccountNumber = toAccountNumber,
            amount = amount,
            outcome = if (reason == null) InterbankOutcome.ACCEPTED else InterbankOutcome.REJECTED,
            rejectReason = reason,
        )
    }
}
