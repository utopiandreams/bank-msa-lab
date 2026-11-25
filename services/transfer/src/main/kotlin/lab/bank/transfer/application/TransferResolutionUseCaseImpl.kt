package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.TransferRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * 한 회차 = **한 트랜잭션** (6주 차 OutboxRelay와 같은 모양):
 *   잡기(FOR UPDATE SKIP LOCKED) → 한 건씩 core 조회 → 상태 반영 + outbox → 커밋
 *
 * - 잠금은 커밋 때 풀린다. 그동안 다른 파드의 일꾼은 이 행들을 건너뛴다 (ADR-0005 결정 5)
 * - 잠금을 쥔 동안 같은 송금에 대한 고객 재요청의 UPDATE는 기다렸다가 version 충돌로 진다 → 고객 쪽이 다시 읽는다 (결정 4).
 *   그래서 일꾼 쪽은 자기가 잡은 행에서 충돌로 지는 일이 없다 (잡은 순간부터 커밋까지 아무도 그 행을 못 바꾼다)
 * - 대가: 조회(원격 호출)하는 동안 DB 커넥션과 행 잠금을 쥔다 → batchSize를 작게, 조회가 실패하면(core 장애) 회차를 바로 끝낸다.
 *   core가 죽어 있으면 건마다 타임아웃(최대 4초)씩 기다리고, 그동안 고객 재요청도 잠금에 막히기 때문
 */
@Service
class TransferResolutionUseCaseImpl(
    private val transactionTemplate: TransactionTemplate,
    private val transferRepository: TransferRepository,
    private val resolver: TransferResolver,
    private val properties: ResolutionProperties,
) : TransferResolutionUseCase {

    override fun resolveStuck(): Int =
        transactionTemplate.execute {
            val sentBefore = Instant.now().minus(properties.checkAfter)
            val batch = transferRepository.findUnresolvedForUpdate(sentBefore, properties.batchSize)
            var resolved = 0
            for (transfer in batch) {
                val after = resolver.resolve(transfer)
                // 기준 시간이 지난 것만 잡았으니, 조회 뒤에도 끝 상태가 아니면 = 조회 자체가 실패(core 장애·타임아웃).
                // 나머지도 같은 이유로 건마다 타임아웃만큼 기다리며 실패할 것 → 이번 회차는 여기서 끝 (잠금을 오래 쥐지 않게)
                if (!after.status.isFinal) break
                resolved++
            }
            resolved
        }

}
