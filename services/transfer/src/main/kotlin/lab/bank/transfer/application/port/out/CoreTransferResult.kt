package lab.bank.transfer.application.port.out

import java.util.UUID

/**
 * core에 송금을 요청한 결과. 송금 상태 전이와 1:1로 대응한다.
 *
 *   Completed → transfer.completeProcessing(coreEntryId)
 *   Rejected  → transfer.failedProcessing(code)
 *   Unknown   → transfer.markUnknown()
 *
 * 어떤 core 응답을 어디로 분류할지는 어댑터가 정한다 (T-1 설계 질문 2번).
 * 기준: Rejected는 "core가 처리하지 않았음이 확실할 때"만. 조금이라도 애매하면 Unknown.
 */
sealed interface CoreTransferResult {

    // core가 거래를 커밋했다. 재요청(replayed)이었어도 같은 거래 ID가 온다
    data class Completed(val coreEntryId: UUID) : CoreTransferResult

    // core가 처리하지 않고 거절했다 (확실한 실패). code는 core가 준 업무 에러 코드 그대로
    data class Rejected(val code: String) : CoreTransferResult

    // core가 처리했는지 모른다. reason은 로그·디버깅용 (예: "read timeout", "503")
    data class Unknown(val reason: String) : CoreTransferResult
}
