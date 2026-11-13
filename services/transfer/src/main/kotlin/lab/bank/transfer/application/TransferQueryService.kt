package lab.bank.transfer.application

import lab.bank.transfer.application.port.out.TransferRepository
import lab.bank.transfer.domain.Transfer
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TransferQueryService(
    private val transferRepository: TransferRepository,
) : TransferQuery {

    override fun find(id: UUID): Transfer? = transferRepository.findById(id)
}
