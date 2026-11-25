package lab.bank.bankmock.application

import lab.bank.bankmock.domain.InterbankTransfer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 판정 규칙 단위 테스트용 가짜 저장소. DB 동작(동시 저장·재시작)은 JdbcInterbankTransferStoreTest가 진짜 Postgres로 본다 */
class InMemoryInterbankTransferStore : InterbankTransferStore {
    private val rows = ConcurrentHashMap<String, InterbankTransfer>()

    override fun find(idempotencyKey: String) = rows[idempotencyKey]

    override fun saveIfAbsent(candidate: InterbankTransfer) = rows.putIfAbsent(candidate.idempotencyKey, candidate) ?: candidate

    override fun processedBetween(from: Instant, to: Instant) =
        rows.values.filter { it.processedAt >= from && it.processedAt < to }.sortedWith(compareBy({ it.processedAt }, { it.idempotencyKey }))
}
