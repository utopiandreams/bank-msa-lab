package lab.bank.transfer

import lab.bank.transfer.application.port.out.CoreInterbankPort
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.application.port.out.InterbankWithdrawCommand
import java.util.UUID

/**
 * 가짜 core (타행 출금·출금 취소). 결과를 마음대로 정해 사가의 분기(완료·거절·결과 불명)를 만든다.
 * 기본: 둘 다 완료
 */
class FakeCoreInterbankPort : CoreInterbankPort {

    var withdrawRespond: (InterbankWithdrawCommand) -> CoreTransferResult = { CoreTransferResult.Completed(UUID.randomUUID()) }
    var cancelRespond: (UUID) -> CoreTransferResult = { CoreTransferResult.Completed(UUID.randomUUID()) }
    val withdrawCalls = mutableListOf<InterbankWithdrawCommand>()
    val cancelCalls = mutableListOf<UUID>()

    override fun withdraw(command: InterbankWithdrawCommand): CoreTransferResult {
        withdrawCalls += command
        return withdrawRespond(command)
    }

    override fun cancelWithdraw(transferId: UUID): CoreTransferResult {
        cancelCalls += transferId
        return cancelRespond(transferId)
    }

    fun reset() {
        withdrawRespond = { CoreTransferResult.Completed(UUID.randomUUID()) }
        cancelRespond = { CoreTransferResult.Completed(UUID.randomUUID()) }
        withdrawCalls.clear()
        cancelCalls.clear()
    }
}
