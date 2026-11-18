package lab.bank.transfer.application.port.out

/**
 * 외부 결제망(타행)에 이체를 보낸 결과 (ADR-0004).
 *
 *   Accepted → 피벗 통과. 받는 은행이 입금했다. 되돌릴 수 없다 → 앞으로만 (COMPLETED)
 *   Rejected → 받는 은행이 거절했다. 돈은 안 나갔다 → 출금 보상 (COMPENSATING → CANCELLED)
 *   Unknown  → 처리됐는지 모른다. **보상하면 안 된다** (실제로 입금됐다면 이중 지급). 확인 먼저 (8주 차)
 */
sealed interface ExternalTransferResult {

    data object Accepted : ExternalTransferResult

    // reason: 외부가 준 거절 사유 그대로 (ACCOUNT_NOT_FOUND, RECEIVING_BANK_REJECTED)
    data class Rejected(val reason: String) : ExternalTransferResult

    // reason: 로그·디버깅용 (예: "read timeout", "503")
    data class Unknown(val reason: String) : ExternalTransferResult
}
