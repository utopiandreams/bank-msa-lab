package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.CorePostingPort
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.CoreTransferResult.Completed
import lab.bank.transfer.application.port.out.CoreTransferResult.Rejected
import lab.bank.transfer.application.port.out.CoreTransferResult.Unknown
import lab.bank.transfer.application.port.out.DuplicateIdempotencyKeyException
import lab.bank.transfer.application.port.out.TransferEventPublisher
import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.IdempotencyConflictException
import lab.bank.transfer.domain.IdempotencyKey
import lab.bank.transfer.domain.SameAccountsException
import lab.bank.transfer.domain.Transfer
import lab.bank.transfer.domain.TransferStatus.COMPLETED
import lab.bank.transfer.domain.TransferStatus.FAILED
import lab.bank.transfer.domain.TransferStatus.PROCESSING
import lab.bank.transfer.domain.TransferStatus.REQUESTED
import lab.bank.transfer.domain.TransferStatus.UNKNOWN
import lab.bank.transfer.domain.UserId
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class TransferUseCaseImpl(
    private val transactionTemplate: TransactionTemplate,
    private val transferRepository: TransferRepository,
    private val corePostingPort: CorePostingPort,
    private val transferResolver: TransferResolver,
    private val eventPublisher: TransferEventPublisher,
    private val resolutionProperties: ResolutionProperties,
) : TransferUseCase {

    /**
     * 같은 요청자의 같은 고객 멱등 키 송금이 있으면 재요청으로 처리하고(replay), 없으면 새로 만든다.
     *
     * 처리중 커밋 → 트랜잭션 밖에서 core 호출 → 결과 커밋 (T-1). core 호출 중엔 DB 커넥션을 쥐고 있지 않다.
     */
    override fun transfer(requesterId: UserId, idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): TransferRequestResult {
        validate(fromAccountId, toAccountId)
        // 키 범위 = 요청자별 (Flyway V3). 다른 사용자가 같은 키를 써도 서로 보이지 않는다
        transferRepository.findByIdempotencyKey(requesterId, idempotencyKey)?.let { return replay(it, fromAccountId, toAccountId, amount) }

        val processing = try {
            startInTransaction(Transfer.request(requesterId, idempotencyKey, fromAccountId, toAccountId, amount))
        } catch (e: DuplicateIdempotencyKeyException) {
            // 같은 키 동시 요청에서 늦은 쪽: 둘 다 "없음"을 보고 INSERT했고 먼저 커밋한 쪽이 이겼다.
            // 트랜잭션 밖에서 잡았으니 새로 읽으면 이긴 쪽의 송금이 보인다 → 재요청으로 처리 (재시도는 이 한 번뿐)
            // 키 UNIQUE는 당행·타행 통틀어 하나인데 당행으로는 안 보인다 = 이 키는 타행 송금이 쓰고 있다 (소거법).
            // PostgreSQL은 상대 행이 커밋된 뒤에만 UNIQUE 위반을 확정하므로 "아직 안 보이는" 경우는 없다
            val winner = transferRepository.findByIdempotencyKey(requesterId, idempotencyKey)
                ?: throw IdempotencyConflictException(idempotencyKey, "타행 송금에 이미 쓰인 멱등 키입니다")
            return replay(winner, fromAccountId, toAccountId, amount)
        }

        val result = corePostingPort.transfer(processing)
        return TransferRequestResult(applyResultInTransaction(processing, result), replayed = false)
    }

    /**
     * 같은 키 재요청. 새 송금을 만들지 않는다 (replayed = true). **core에 송금을 다시 보내지 않는다** (ADR-0005 결정 2)
     * - 내용이 다름                  → 409 (키를 재사용한 클라이언트 버그)
     * - COMPLETED / FAILED          → 지금 상태 그대로
     * - PROCESSING / UNKNOWN        → 보낸 지 기준 시간(5초)이 지났으면 core에 조회해서 확정, 안 지났으면 지금 상태 그대로
     */
    private fun replay(existing: Transfer, fromAccountId: UUID, toAccountId: UUID, amount: Amount): TransferRequestResult {
        if (!existing.isSameRequestAs(fromAccountId, toAccountId, amount)) throw IdempotencyConflictException(existing.idempotencyKey)
        val transfer = when (existing.status) {
            REQUESTED, COMPLETED, FAILED -> existing
            PROCESSING, UNKNOWN -> if (isDueForCheck(existing)) resolveOrReread(existing) else existing
        }
        return TransferRequestResult(transfer, replayed = true)
    }

    /**
     * 조회해도 되는 때인가: core에 보낸 시각(createdAt) + checkAfter가 지났나 (ADR-0005 결정 1).
     * 더 일찍 조회하면 core가 아직 처리 중인데 "없음"을 보고 실패로 오판한다 (늦은 커밋).
     * updatedAt이 아니라 createdAt: UNKNOWN이 되면 updatedAt이 바뀌어 보낸 시각을 잃는다. 재전송이 없으니 보낸 시각은 평생 하나.
     * 해소 일꾼도 같은 기준으로 대상을 고른다 (TransferRepository.findUnresolvedForUpdate)
     */
    private fun isDueForCheck(transfer: Transfer): Boolean =
        transfer.createdAt.isBefore(Instant.now().minus(resolutionProperties.checkAfter))

    /**
     * 조회로 확정한다. 같은 송금을 해소 일꾼(또는 다른 재요청)이 먼저 확정했으면 저장에서 version 충돌로 진다 (ADR-0005 결정 4)
     * → 이긴 쪽이 이미 커밋한 상태를 다시 읽어서 돌려준다. 고객에게 500을 주지 않는다 (송금은 실제로 확정됐으니까).
     * 다시 읽기는 트랜잭션 밖이라 이긴 쪽의 커밋이 보인다. 송금은 지우지 않으므로 없으면 버그 → 시끄럽게 실패 (500 + 에러 로그)
     */
    private fun resolveOrReread(transfer: Transfer): Transfer =
        try {
            transferResolver.resolve(transfer)
        } catch (e: OptimisticLockingFailureException) {
            transferRepository.findById(transfer.id) ?: error("송금이 사라졌다: ${transfer.id}")
        }

    // 금액 검사는 없다: Amount 타입으로 들어온 순간 이미 검증됨 (컨트롤러에서 변환)
    private fun validate(fromAccountId: UUID, toAccountId: UUID) {
        if (fromAccountId == toAccountId) throw SameAccountsException(fromAccountId, toAccountId)
    }

    /**
     * core 결과를 반영하고 커밋한다. 끝 상태로 **바뀌었으면** 같은 트랜잭션에서 이벤트를 outbox에 적는다 (ADR-0003 결정 2, 6주 차 Outbox).
     *
     * 처음 요청의 결과만 여기로 온다 (PROCESSING → 끝 / UNKNOWN). 멈춘 송금을 조회로 끝 상태로 만드는 곳은 TransferResolver (8주 차).
     * 끝 상태로 바뀌는 곳이 이 둘뿐이고, 이미 끝난 송금을 돌려줄 때(replay의 COMPLETED/FAILED)는 어느 쪽도 안 지나므로 중복 발행이 없다.
     * UNKNOWN은 끝 상태가 아니라 발행하지 않는다.
     *
     * 상태 저장과 outbox INSERT가 함께 커밋되거나 함께 롤백된다 → 커밋과 발행 사이 유실도, 롤백됐는데 "완료" 알림이 나가는 유령 이벤트도 없다.
     * Kafka 전송은 릴레이가 커밋 뒤에 한다. (이전: 커밋 뒤 트랜잭션 밖에서 바로 발행 → 그 사이에 죽으면 유실, TransferEventLossTest)
     */
    private fun applyResultInTransaction(transfer: Transfer, result: CoreTransferResult): Transfer {
        when (result) {
            is Completed -> transfer.completeProcessing(result.coreEntryId)
            is Rejected -> transfer.failedProcessing(result.code)
            is Unknown -> transfer.markUnknown()
        }
        return saveInTransaction(transfer)
    }

    private fun startInTransaction(transfer: Transfer): Transfer =
        transactionTemplate.execute {
            transfer.startProcessing()
            transferRepository.save(transfer)
        }

    private fun saveInTransaction(transfer: Transfer) =
        transactionTemplate.execute {
            val saved = transferRepository.save(transfer)
            if (saved.status.isFinal) eventPublisher.publishFinished(saved)
            saved
        }

}