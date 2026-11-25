package lab.bank.bankmock.application

import lab.bank.bankmock.domain.InterbankTransfer
import java.time.Instant

/** 처리 기록 저장소. 기록은 한 번 쓰면 바뀌지 않는다 (판정은 한 번, 재요청엔 그 판정 그대로) */
interface InterbankTransferStore {

    fun find(idempotencyKey: String): InterbankTransfer?

    /**
     * 처음 보는 키면 저장하고 [candidate]를, 이미 있으면 **먼저 저장된 기록**을 돌려준다.
     * 같은 키가 동시에 와서 둘 다 판정했어도 남는 판정은 하나, 둘 다 같은 답을 받는다
     */
    fun saveIfAbsent(candidate: InterbankTransfer): InterbankTransfer

    /** [from, to) 사이에 판정한 기록, 처리 시각 → 키 순 */
    fun processedBetween(from: Instant, to: Instant): List<InterbankTransfer>
}
