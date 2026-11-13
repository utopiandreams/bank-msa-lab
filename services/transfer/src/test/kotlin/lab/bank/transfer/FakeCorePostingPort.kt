package lab.bank.transfer

import lab.bank.transfer.application.port.out.CorePostingPort
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.domain.Transfer
import java.util.UUID

/**
 * 가짜 core. 헥사고날의 보상: 포트만 바꿔 끼우면 core 없이 완료·거절·결과 불명을 마음대로 만든다.
 *
 * respond: 이번 호출에 돌려줄 결과 (기본: 완료)
 * onCall:  호출되는 순간 실행할 검사 (예: "지금 트랜잭션이 열려 있나?")
 */
class FakeCorePostingPort : CorePostingPort {

    var respond: (Transfer) -> CoreTransferResult = { CoreTransferResult.Completed(UUID.randomUUID()) }
    var onCall: (Transfer) -> Unit = {}
    val calls = mutableListOf<Transfer>()

    override fun transfer(transfer: Transfer): CoreTransferResult {
        calls += transfer
        onCall(transfer)
        return respond(transfer)
    }

    fun reset() {
        respond = { CoreTransferResult.Completed(UUID.randomUUID()) }
        onCall = {}
        calls.clear()
    }
}
