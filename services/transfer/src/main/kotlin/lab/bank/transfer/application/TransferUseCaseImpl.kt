package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.CorePostingPort
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.CoreTransferResult.Completed
import lab.bank.transfer.application.port.out.CoreTransferResult.Rejected
import lab.bank.transfer.application.port.out.CoreTransferResult.Unknown
import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.Amount
import lab.bank.transfer.domain.SameAccountsException
import lab.bank.transfer.domain.Transfer
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class TransferUseCaseImpl(
    private val transactionTemplate: TransactionTemplate,
    private val transferRepository: TransferRepository,
    private val corePostingPort: CorePostingPort
) : TransferUseCase {


    override fun transfer(fromAccountId: UUID, toAccountId: UUID, amount: Amount): Transfer {
        validate(fromAccountId, toAccountId)
        val transfer = startInTransaction(fromAccountId, toAccountId, amount)
        val transferResult = corePostingPort.transfer(transfer)
        return applyResultInTransaction(transfer, transferResult)
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

    private fun startInTransaction(fromAccountId: UUID, toAccountId: UUID, amount: Amount): Transfer =
        transactionTemplate.execute {
            val transfer = Transfer.request(fromAccountId, toAccountId, amount)
            transfer.startProcessing()
            transferRepository.save(transfer)
        }

    private fun saveInTransaction(transfer: Transfer) =
        transactionTemplate.execute { transferRepository.save(transfer) }

}