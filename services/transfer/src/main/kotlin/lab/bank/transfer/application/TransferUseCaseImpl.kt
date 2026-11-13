package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.CorePostingPort
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.CoreTransferResult.Completed
import lab.bank.transfer.application.port.out.CoreTransferResult.Rejected
import lab.bank.transfer.application.port.out.CoreTransferResult.Unknown
import lab.bank.transfer.application.port.out.DuplicateIdempotencyKeyException
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
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class TransferUseCaseImpl(
    private val transactionTemplate: TransactionTemplate,
    private val transferRepository: TransferRepository,
    private val corePostingPort: CorePostingPort
) : TransferUseCase {


    /**
     * 같은 고객 멱등 키의 송금이 있으면 재요청으로 처리하고(replay), 없으면 새로 만든다.
     *
     * 처리중 커밋 → 트랜잭션 밖에서 core 호출 → 결과 커밋 (T-1). core 호출 중엔 DB 커넥션을 쥐고 있지 않다.
     */
    override fun transfer(idempotencyKey: IdempotencyKey, fromAccountId: UUID, toAccountId: UUID, amount: Amount): TransferRequestResult {
        validate(fromAccountId, toAccountId)
        transferRepository.findByIdempotencyKey(idempotencyKey)?.let { return replay(it, fromAccountId, toAccountId, amount) }

        val processing = try {
            startInTransaction(Transfer.request(idempotencyKey, fromAccountId, toAccountId, amount))
        } catch (e: DuplicateIdempotencyKeyException) {
            // 같은 키 동시 요청에서 늦은 쪽: 둘 다 "없음"을 보고 INSERT했고 먼저 커밋한 쪽이 이겼다.
            // 트랜잭션 밖에서 잡았으니 새로 읽으면 이긴 쪽의 송금이 보인다 → 재요청으로 처리 (재시도는 이 한 번뿐)
            val winner = transferRepository.findByIdempotencyKey(idempotencyKey)
                ?: throw IllegalStateException("멱등 키 중복인데 송금이 없음: ${idempotencyKey.value}", e)
            return replay(winner, fromAccountId, toAccountId, amount)
        }

        val result = corePostingPort.transfer(processing)
        return TransferRequestResult(applyResultInTransaction(processing, result), replayed = false)
    }

    /**
     * 같은 키 재요청. 새 송금을 만들지 않는다 (replayed = true)
     * - 내용이 다름                      → 409 (키를 재사용한 클라이언트 버그)
     * - COMPLETED / FAILED / PROCESSING  → 지금 상태 그대로 (core 호출 없음 = 이중 송금 방지)
     * - UNKNOWN                          → 같은 송금 ID로 core에 재전송해 확정. core가 송금 ID로 멱등하니 이미 처리됐어도 안전
     */
    private fun replay(existing: Transfer, fromAccountId: UUID, toAccountId: UUID, amount: Amount): TransferRequestResult {
        if (!existing.isSameRequestAs(fromAccountId, toAccountId, amount)) throw IdempotencyConflictException(existing.idempotencyKey)
        val transfer = when (existing.status) {
            REQUESTED, PROCESSING, COMPLETED, FAILED -> existing
            UNKNOWN -> applyResultInTransaction(existing, corePostingPort.transfer(existing))
        }
        return TransferRequestResult(transfer, replayed = true)
    }

    // 금액 검사는 없다: Amount 타입으로 들어온 순간 이미 검증됨 (컨트롤러에서 변환)
    private fun validate(fromAccountId: UUID, toAccountId: UUID) {
        if (fromAccountId == toAccountId) throw SameAccountsException(fromAccountId, toAccountId)
    }

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
        transactionTemplate.execute { transferRepository.save(transfer) }

}