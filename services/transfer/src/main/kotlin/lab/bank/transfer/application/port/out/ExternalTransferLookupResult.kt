package lab.bank.transfer.application.port.out

/**
 * 외부 결제망에 타행 이체가 처리됐는지 **조회**한 결과 (8주 차 2부, 설계 연습 08 결정 1).
 *
 * 송금 결과([ExternalTransferResult])와 타입을 나눈 이유: NotFound가 더 있다. NotFound는 "외부에 기록이 없다"는 관찰이고,
 * **외부 처리 상한이 지난 뒤에 물었을 때만** "앞으로도 처리하지 않는다"로 믿을 수 있다 (판단은 유스케이스. 어댑터는 관찰만 전한다)
 *
 *   Accepted     → 받는 은행이 입금했다. 되돌릴 수 없다 (피벗 통과)
 *   Rejected     → 받는 은행이 거절했다. 돈은 안 나갔다
 *   NotFound     → 외부에 기록이 없다 (받지 못했거나, 처리 상한을 넘겨 포기했다)
 *   Unknown      → 조회 자체가 실패했다 (타임아웃, 5xx, 응답을 못 읽음) → 아무것도 바꾸지 않고 다음 기회에
 */
sealed interface ExternalTransferLookupResult {

    data object Accepted : ExternalTransferLookupResult

    data class Rejected(val reason: String) : ExternalTransferLookupResult

    data object NotFound : ExternalTransferLookupResult

    data class Unknown(val reason: String) : ExternalTransferLookupResult
}
