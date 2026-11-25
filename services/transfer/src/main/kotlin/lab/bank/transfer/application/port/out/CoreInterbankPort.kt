package lab.bank.transfer.application.port.out

import java.util.UUID

/**
 * core에 타행 이체의 출금·출금 취소(보상)를 요청하는 포트 (ADR-0004). 예외를 던지지 않고 결과를 셋 중 하나로 돌려준다.
 *
 * 결과 분류 (어댑터 기준, 당행 CorePostingPort와 같은 원칙: Rejected는 "처리 안 됨이 확실할 때만"):
 *
 * withdraw (POST /postings/interbank-withdrawals, Idempotency-Key = 송금 ID)
 *   Completed - 출금됨 (201, 같은 키 재요청이면 200). coreEntryId = 출금 전표
 *   Rejected  - core가 거절, 롤백됨 (잔액 부족 422, 계좌 없음·남의 계좌 404, 입력 오류 400) → 송금 FAILED
 *   Unknown   - 타임아웃·5xx·409 → 출금됐는지 모름
 *
 * cancelWithdraw (POST /postings/interbank-withdrawals/{송금ID}/cancel)
 *   Completed - 취소됨 (201, 이미 취소돼 있었으면 200). coreEntryId = 취소 전표
 *   Unknown   - 그 밖 전부. **취소에는 Rejected가 없다**: 보상은 실패로 끝날 수 없다 (ADR-0004).
 *               404(원래 출금 없음)·422(타행 출금 아님)는 정상 흐름에선 나올 수 없는 우리 쪽 버그라, "끝났다"로 보면 안 되고
 *               COMPENSATING에 남겨 사람이 보게 한다 (에러 로그)
 *
 * lookupWithdraw (GET /postings/{송금ID}, 8주 차 2부) - 타행 출금이 됐는지 조회. 당행 조회(CorePostingPort.lookup)와 같은 API·같은 분류
 *   (core는 거래 종류와 상관없이 멱등 키로 전표를 찾는다). NotProcessed는 core 트랜잭션 타임아웃이 지난 뒤에만 "출금 안 됨"으로 믿는다
 */
interface CoreInterbankPort {

    fun withdraw(command: InterbankWithdrawCommand): CoreTransferResult

    fun cancelWithdraw(transferId: UUID): CoreTransferResult

    fun lookupWithdraw(transferId: UUID): CoreTransferLookupResult
}
