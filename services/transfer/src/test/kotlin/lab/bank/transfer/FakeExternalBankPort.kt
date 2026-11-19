package lab.bank.transfer

import lab.bank.transfer.application.port.out.ExternalBankPort
import lab.bank.transfer.application.port.out.ExternalTransferCommand
import lab.bank.transfer.application.port.out.ExternalTransferResult

/**
 * 가짜 외부 결제망. 기본: 수락
 * onCall: 호출되는 순간 실행할 검사 (예: "의도 상태가 이미 커밋됐나?", "트랜잭션이 열려 있나?")
 */
class FakeExternalBankPort : ExternalBankPort {

    var respond: (ExternalTransferCommand) -> ExternalTransferResult = { ExternalTransferResult.Accepted }
    var onCall: (ExternalTransferCommand) -> Unit = {}
    val calls = mutableListOf<ExternalTransferCommand>()

    override fun send(command: ExternalTransferCommand): ExternalTransferResult {
        calls += command
        onCall(command)
        return respond(command)
    }

    fun reset() {
        respond = { ExternalTransferResult.Accepted }
        onCall = {}
        calls.clear()
    }
}
