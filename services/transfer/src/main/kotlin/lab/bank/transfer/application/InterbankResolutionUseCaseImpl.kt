package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.InterbankTransferRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * 한 회차 = **건마다 한 트랜잭션**, 최대 batchSize번 반복 (설계 연습 08 2부 결정 7):
 *   [트랜잭션: 1건 잡기(FOR UPDATE SKIP LOCKED) → 조회·(필요하면) 취소 → 상태 반영 + outbox → 커밋] × N
 *
 * 당행(TransferResolutionUseCaseImpl, 회차 = 한 트랜잭션)과 다른 이유: 타행은 한 건에 원격 호출이 둘(조회 + 취소)이라
 * 회차를 묶으면 최악 10건 × 8초 = 80초. 잠금 대기·커넥션 고갈은 거의 없지만(멈춘 행에 쓰는 쪽이 일꾼뿐, 스레드 하나)
 * 커밋 단위가 크면 해소가 회차 끝까지 안 보이고, 죽으면 통째로 다시 하고, graceful shutdown(30s)을 넘긴다.
 *
 * 잠금은 원격 호출 동안 쥔다: 다른 파드의 일꾼이 같은 송금을 건너뛰게(일 나누기). 정합성 자체는 멱등성이 지킨다
 * (조회는 읽기, 취소는 core reverses_entry_id UNIQUE). Resolver 안의 transactionTemplate.execute는 이 트랜잭션에 합류한다 (REQUIRED).
 *
 * 회차를 끝내는 조건:
 *   - 잡을 게 없다
 *   - 해소했는데 끝 상태가 아니다 = 조회·취소가 실패했다 (상대 장애). 계속하면 건마다 타임아웃만큼 기다릴 것이고,
 *     실패한 행은 저장하지 않아 updatedAt이 그대로라(결정 2) 다음 반복에서 **같은 행을 또 잡는다**
 */
@Service
class InterbankResolutionUseCaseImpl(
    private val transactionTemplate: TransactionTemplate,
    private val repository: InterbankTransferRepository,
    private val resolver: InterbankTransferResolver,
    private val properties: ResolutionProperties,
) : InterbankResolutionUseCase {

    override fun resolveStuck(): Int {
        var resolved = 0
        repeat(properties.batchSize) {
            when (resolveOne()) {
                Outcome.NOTHING_TO_DO, Outcome.STILL_STUCK -> return resolved
                Outcome.RESOLVED -> resolved++
            }
        }
        return resolved
    }

    private fun resolveOne(): Outcome =
        transactionTemplate.execute {
            val now = Instant.now()
            val transfer = repository.findStuckForUpdate(
                coreSentBefore = now.minus(properties.checkAfter),
                externalSentBefore = now.minus(properties.externalCheckAfter),
                limit = 1,
            ).firstOrNull() ?: return@execute Outcome.NOTHING_TO_DO

            if (resolver.resolve(transfer).status.isFinal()) Outcome.RESOLVED else Outcome.STILL_STUCK
        } ?: Outcome.NOTHING_TO_DO

    private enum class Outcome { NOTHING_TO_DO, RESOLVED, STILL_STUCK }
}
