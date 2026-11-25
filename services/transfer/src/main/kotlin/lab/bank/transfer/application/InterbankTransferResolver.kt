package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.CoreInterbankPort
import lab.bank.transfer.application.port.out.CoreTransferLookupResult
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.ExternalBankPort
import lab.bank.transfer.application.port.out.ExternalTransferLookupResult
import lab.bank.transfer.application.port.out.InterbankTransferRepository
import lab.bank.transfer.application.port.out.TransferEventPublisher
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.InterbankTransferStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class InterbankTransferResolver(
    private val coreInterbankPort: CoreInterbankPort,
    private val repository: InterbankTransferRepository,
    private val eventPublisher: TransferEventPublisher,
    private val transactionTemplate: TransactionTemplate,
    private val externalBankPort: ExternalBankPort,
) {

    fun resolve(transfer: InterbankTransfer): InterbankTransfer {
        val before = transfer.status
        val resolved = handelUnresolved(transfer)
        if (resolved.status.isFinal() || resolved.status == before) return resolved
        return resolve(resolved)
    }

    fun handelUnresolved(transfer: InterbankTransfer): InterbankTransfer =
        when (transfer.status) {
            InterbankTransferStatus.WITHDRAW_REQUESTED -> lookupWithdrawResult(transfer)
            InterbankTransferStatus.WITHDRAWN -> cancelWithdrawRequest(transfer)
            InterbankTransferStatus.EXTERNAL_BANK_REQUESTED -> lookupExternalBankResult(transfer)
            InterbankTransferStatus.WITHDRAW_CANCEL_REQUESTED -> cancelWithdraw(transfer)
            InterbankTransferStatus.FAILED, InterbankTransferStatus.CANCELLED, InterbankTransferStatus.COMPLETED -> return transfer
            InterbankTransferStatus.REQUESTED -> error("존재할 수 없는 InterbankTranfer 상태 id:${transfer.id}, status:${transfer.status}")
        }

    private fun lookupWithdrawResult(transfer: InterbankTransfer): InterbankTransfer {
        when(val result = coreInterbankPort.lookupWithdraw(transfer.id)) {
            is CoreTransferLookupResult.Completed -> transfer.withdrawn(result.coreEntryId)
            is CoreTransferLookupResult.NotProcessed -> transfer.withdrawRejected("CORE_NOT_PROCESSED")
            is CoreTransferLookupResult.Unknown -> return transfer
        }
        return save(transfer)
    }

    private fun cancelWithdrawRequest(transfer: InterbankTransfer): InterbankTransfer =
        save(transfer.apply { withdrawCancelRequested() })

    private fun lookupExternalBankResult(transfer: InterbankTransfer): InterbankTransfer {
        when (val result = externalBankPort.lookup(transfer.id)) {
            is ExternalTransferLookupResult.Accepted -> transfer.completed()
            is ExternalTransferLookupResult.NotFound -> transfer.withdrawCancelRequested()
            is ExternalTransferLookupResult.Rejected -> transfer.externalBankRejected(result.reason)
            is ExternalTransferLookupResult.Unknown -> return transfer
        }
        return save(transfer)
    }

    private fun cancelWithdraw(transfer: InterbankTransfer): InterbankTransfer {
        when(val result = coreInterbankPort.cancelWithdraw(transfer.id)) {
            is CoreTransferResult.Completed -> transfer.cancelled(result.coreEntryId)
            is CoreTransferResult.Rejected -> error("출금 취소 요청에는 없는 응답입니다. id=${transfer.id}")
            is CoreTransferResult.Unknown -> return transfer
        }
        return save(transfer)
    }

    private fun save(transfer: InterbankTransfer): InterbankTransfer =
        transactionTemplate.execute {
            val saved = repository.save(transfer)
            if (saved.status.isFinal()) eventPublisher.publishFinished(saved)
            saved
        }

}