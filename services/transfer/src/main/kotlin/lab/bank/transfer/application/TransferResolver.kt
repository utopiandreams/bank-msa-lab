package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.CorePostingPort
import lab.bank.transfer.application.port.out.CoreTransferLookupResult.Completed
import lab.bank.transfer.application.port.out.CoreTransferLookupResult.NotProcessed
import lab.bank.transfer.application.port.out.CoreTransferLookupResult.Unknown
import lab.bank.transfer.application.port.out.TransferEventPublisher
import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.Transfer
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * 멈춘 당행 송금(PROCESSING·UNKNOWN) 하나를 core **조회**로 확정한다 (ADR-0005 결정 2). 재전송은 하지 않는다.
 * 부르는 쪽 둘: 해소 일꾼(TransferResolutionUseCaseImpl)과 고객 재요청(TransferUseCaseImpl.replay).
 * "보낸 지 기준 시간이 지났나"는 부르는 쪽이 판단한다 (일꾼은 쿼리 조건으로, 고객 재요청은 replay에서).
 *
 * 트랜잭션: 조회(원격 호출)는 부르는 쪽 트랜잭션 상태 그대로, 저장 + outbox는 transactionTemplate으로 묶는다.
 *   - 해소 일꾼: 이미 회차 트랜잭션 안(행 잠금 중) → 조회도 그 안에서, 저장은 그 트랜잭션에 합류 (REQUIRED)
 *   - 고객 재요청: 트랜잭션 밖 → 조회하는 동안 커넥션을 안 쥐고(T-1), 저장할 때만 짧은 트랜잭션
 * 저장에서 version 충돌이 나면 예외가 그대로 올라간다 (ObjectOptimisticLockingFailureException). 처리는 부르는 쪽 몫 (결정 4)
 */
@Component
class TransferResolver(
    private val corePostingPort: CorePostingPort,
    private val transferRepository: TransferRepository,
    private val eventPublisher: TransferEventPublisher,
    private val transactionTemplate: TransactionTemplate,
) {

    /** 반환: 저장 뒤의 송금. 조회가 실패하면 UNKNOWN (끝 상태 아님 → 다음 기회에 다시 조회) */
    fun resolve(transfer: Transfer): Transfer {
        when (val lookupResult = corePostingPort.lookup(transfer)) {
            is Completed -> transfer.completeProcessing(lookupResult.coreEntryId)
            is NotProcessed -> transfer.failedProcessing("CORE_NOT_PROCESSED") // 이거는 잔액부족이거나, 아니면 그냥 뭐 전송 자체가 안되었거나 등등... 실패 코드를 정해야함. 정합성 문제는 없다.
            is Unknown -> transfer.markUnknown()
        }
        return transactionTemplate.execute {
            val saved = transferRepository.save(transfer)
            if (saved.status.isFinal) eventPublisher.publishFinished(saved)   // 같은 트랜잭션 = outbox
            saved
        }
    }
}
