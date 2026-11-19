package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.InterbankTransferRepository
import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.Transfer
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TransferQueryService(
    private val transferRepository: TransferRepository,
    private val interbankTransferRepository: InterbankTransferRepository,
) : TransferQuery {

    override fun find(id: UUID): Transfer? = transferRepository.findById(id)

    override fun findInterbank(id: UUID): InterbankTransfer? = interbankTransferRepository.findById(id)
}
