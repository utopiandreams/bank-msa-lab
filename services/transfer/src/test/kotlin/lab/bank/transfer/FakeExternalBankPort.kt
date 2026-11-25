package lab.bank.transfer

import lab.bank.transfer.application.port.out.ExternalBankPort
import lab.bank.transfer.application.port.out.ExternalTransferCommand
import lab.bank.transfer.application.port.out.ExternalTransferLookupResult
import lab.bank.transfer.application.port.out.ExternalTransferResult
import java.util.UUID

/**
 * 가짜 외부 결제망. 기본: 수락, 조회는 "없음"
 * onCall: 호출되는 순간 실행할 검사 (예: "의도 상태가 이미 커밋됐나?", "트랜잭션이 열려 있나?")
 */
class FakeExternalBankPort : ExternalBankPort {

    var respond: (ExternalTransferCommand) -> ExternalTransferResult = { ExternalTransferResult.Accepted }
    var onCall: (ExternalTransferCommand) -> Unit = {}
    val calls = mutableListOf<ExternalTransferCommand>()
    var lookupRespond: (UUID) -> ExternalTransferLookupResult = { ExternalTransferLookupResult.NotFound }
    val lookupCalls = mutableListOf<UUID>()

    override fun send(command: ExternalTransferCommand): ExternalTransferResult {
        calls += command
        onCall(command)
        return respond(command)
    }

    override fun lookup(transferId: UUID): ExternalTransferLookupResult {
        lookupCalls += transferId
        return lookupRespond(transferId)
    }

    fun reset() {
        respond = { ExternalTransferResult.Accepted }
        onCall = {}
        calls.clear()
        lookupRespond = { ExternalTransferLookupResult.NotFound }
        lookupCalls.clear()
    }
}
