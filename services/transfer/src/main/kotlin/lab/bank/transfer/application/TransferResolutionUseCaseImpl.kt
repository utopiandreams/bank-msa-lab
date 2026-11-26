package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.TransferRepository
import org.slf4j.LoggerFactory
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
    private val metrics: ResolutionMetrics,
) : TransferResolutionUseCase {

    override fun resolveStuck(): Int {
        // 결과(끝 상태 또는 null = 못 풂)를 모았다가 커밋된 뒤에 센다: 회차 중간에 예외로 롤백되면 아무것도 확정되지 않았다
        val outcomes = transactionTemplate.execute {
            val sentBefore = Instant.now().minus(properties.checkAfter)
            val batch = transferRepository.findUnresolvedForUpdate(sentBefore, properties.batchSize)
            val outcomes = mutableListOf<String?>()
            for (transfer in batch) {
                val before = transfer.status   // 값으로 떠 둔다 (resolve가 같은 객체를 바꾼다)
                val after = resolver.resolve(transfer)
                // 송금마다 한 줄: 송금 ID로 로그를 검색하면 접수 → 결과 불명 → 해소까지 이어진다
                if (after.status.isFinal) {
                    log.info("당행 해소: transferId={} {} → {} failureCode={}", transfer.id, before, after.status, after.failureCode)
                } else {
                    log.warn("당행 해소 못 함 (다음 회차에 다시): transferId={} {} → {}", transfer.id, before, after.status)
                }
                // 기준 시간이 지난 것만 잡았으니, 조회 뒤에도 끝 상태가 아니면 = 조회 자체가 실패(core 장애·타임아웃).
                // 나머지도 같은 이유로 건마다 타임아웃만큼 기다리며 실패할 것 → 이번 회차는 여기서 끝 (잠금을 오래 쥐지 않게)
                if (!after.status.isFinal) {
                    outcomes += null
                    break
                }
                outcomes += after.status.name
            }
            outcomes
        } ?: emptyList()
        outcomes.forEach { if (it == null) metrics.stillStuck(ResolutionMetrics.DOMESTIC) else metrics.resolved(ResolutionMetrics.DOMESTIC, it) }
        return outcomes.count { it != null }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TransferResolutionUseCaseImpl::class.java)
    }
}
