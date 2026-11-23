package lab.bank.transfer

import lab.bank.transfer.application.port.out.CorePostingPort
import lab.bank.transfer.application.port.out.CoreTransferLookupResult
import lab.bank.transfer.application.port.out.CoreTransferResult
import lab.bank.transfer.domain.Transfer
import java.util.UUID

/**
 * 가짜 core. 헥사고날의 보상: 포트만 바꿔 끼우면 core 없이 완료·거절·결과 불명을 마음대로 만든다.
 *
 * respond: 이번 호출에 돌려줄 결과 (기본: 완료)
 * onCall:  호출되는 순간 실행할 검사 (예: "지금 트랜잭션이 열려 있나?")
 * lookupRespond / onLookup / lookups: 상태 조회(lookup)용 같은 세 가지 (기본: 처리 안 됨). calls에는 섞이지 않는다
 *   → "재전송 금지"는 calls가 늘지 않는 것으로, "조회했다"는 lookups로 확인한다
 */
class FakeCorePostingPort : CorePostingPort {

    var respond: (Transfer) -> CoreTransferResult = { CoreTransferResult.Completed(UUID.randomUUID()) }
    var onCall: (Transfer) -> Unit = {}
    val calls = mutableListOf<Transfer>()

    var lookupRespond: (Transfer) -> CoreTransferLookupResult = { CoreTransferLookupResult.NotProcessed }
    var onLookup: (Transfer) -> Unit = {}
    val lookups = mutableListOf<Transfer>()

    override fun transfer(transfer: Transfer): CoreTransferResult {
        calls += transfer
        onCall(transfer)
        return respond(transfer)
    }

    override fun lookup(transfer: Transfer): CoreTransferLookupResult {
        lookups += transfer
        onLookup(transfer)
        return lookupRespond(transfer)
    }

    fun reset() {
        respond = { CoreTransferResult.Completed(UUID.randomUUID()) }
        onCall = {}
        calls.clear()
        lookupRespond = { CoreTransferLookupResult.NotProcessed }
        onLookup = {}
        lookups.clear()
    }
}
