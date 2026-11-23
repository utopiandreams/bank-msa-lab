package lab.bank.transfer.application.port.out

import java.util.UUID

/**
 * core에 당행 송금이 처리됐는지 **조회**한 결과 (8주 차 결과 불명 해소, ADR-0005).
 *
 * 송금 요청 결과([CoreTransferResult])와 타입을 나눈 이유:
 *   - Rejected(code)는 "core가 거절했다"는 확실한 사실, NotProcessed는 "지금 조회 시점에 전표가 없다"는 관찰이다.
 *     core는 거절을 기억하지 않으므로 조회로는 거절 사유를 알 수 없고, "아직 처리 중"일 수도 있다 (ADR-0005 결정 3).
 *   - 그래서 NotProcessed를 실패로 확정해도 되는지(보낸 지 5초가 지났나)는 유스케이스가 판단한다. 어댑터는 관찰만 전한다.
 *
 *   Completed    → 전표가 있다: core가 커밋했다 (확실)
 *   NotProcessed → 전표가 없다 (지금 이 순간)
 *   Unknown      → 조회 자체가 실패했다 (타임아웃, 5xx, 응답을 못 읽음) → 아무것도 바꾸지 않고 다음 기회에
 */
sealed interface CoreTransferLookupResult {

    data class Completed(val coreEntryId: UUID) : CoreTransferLookupResult

    data object NotProcessed : CoreTransferLookupResult

    data class Unknown(val reason: String) : CoreTransferLookupResult
}
