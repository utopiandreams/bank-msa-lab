package lab.bank.bankmock.application

import java.time.Duration

/**
 * 처리 상한 안에 판정하지 못해 **처리하지 않고** 포기했다. 기록도 남기지 않는다 (조회하면 "없음") → 503.
 * 보내는 쪽은 5xx를 결과 불명으로 다루고, 상한이 지난 뒤 조회해서 "없음"을 보고 확정한다
 */
class ProcessingDeadlineExceededException(val idempotencyKey: String, deadline: Duration) :
    RuntimeException("처리 상한($deadline) 안에 처리하지 못해 포기: $idempotencyKey")
