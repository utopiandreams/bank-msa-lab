package lab.bank.transfer.application.port.out

import lab.bank.transfer.domain.Transfer

/**
 * core에 거래(당행 송금)를 요청하는 포트 (아웃바운드). 유스케이스는 HTTP·상태 코드·타임아웃을 모른다.
 * 구현(어댑터)이 core 응답을 [CoreTransferResult] 셋 중 하나로 분류해서 돌려준다.
 *
 * core 계약: POST /postings/transfers, 헤더 Idempotency-Key = transfer.id (송금 ID가 곧 core 쪽 멱등 키)
 *   → 같은 송금을 다시 보내도 core는 한 번만 처리한다. 그래서 UNKNOWN 뒤 재전송·상태 조회가 안전하다.
 *
 * 인자를 원시 값(from, to: UUID ...)이 아니라 Transfer로 받는 이유: 보내는/받는 계좌가 둘 다 UUID라
 * 순서를 바꿔 넘겨도 컴파일이 통과한다. 돈 방향이 뒤집히는 실수를 시그니처에서 막는다.
 *
 * 예외를 던지지 않는다: 타임아웃·연결 실패·5xx도 [CoreTransferResult.Unknown]으로 돌려준다.
 * 실패를 예외로 두면 유스케이스에서 catch를 빠뜨렸을 때 "모름"이 조용히 사라지기 때문 (sealed + when으로 처리 누락을 컴파일러가 잡게).
 *
 * 8주 차: 상태 조회(GET /postings/{key})로 UNKNOWN을 해소하는 메서드를 여기에 추가한다.
 */
interface CorePostingPort {

    fun transfer(transfer: Transfer): CoreTransferResult
}
