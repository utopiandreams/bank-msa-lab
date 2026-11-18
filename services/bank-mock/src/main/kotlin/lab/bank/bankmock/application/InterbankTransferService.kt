package lab.bank.bankmock.application

import lab.bank.bankmock.domain.InterbankOutcome
import lab.bank.bankmock.domain.InterbankTransfer
import lab.bank.bankmock.domain.RejectReason
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 외부 결제망 흉내: 받은 이체를 성공/거절로 판정하고, 같은 키는 처음 판정을 그대로 돌려준다.
 *
 * 저장은 메모리. 남의 은행이라 우리가 내구성을 책임질 대상이 아니다 (ADR-0004).
 * 한계: 재시작하면 기록이 사라져 같은 키가 "처음"으로 보인다 → 8주 차 "같은 키 재전송으로 확인"에서 문제가 되면 DB를 붙인다.
 */
@Service
class InterbankTransferService(
    private val properties: BankMockProperties,
    private val random: Random = Random.Default,
) {
    private val transfers = ConcurrentHashMap<String, InterbankTransfer>()

    fun transfer(idempotencyKey: String, toBankCode: String, toAccountNumber: String, amount: Long): InterbankTransfer {
        // computeIfAbsent: 같은 키가 동시에 와도 판정은 한 번만 일어나고 둘 다 같은 결과를 받는다
        val recorded = transfers.computeIfAbsent(idempotencyKey) {
            judge(idempotencyKey, toBankCode, toAccountNumber, amount)
        }
        if (!recorded.sameRequestAs(toBankCode, toAccountNumber, amount)) {
            throw IdempotencyConflictException(idempotencyKey)
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
