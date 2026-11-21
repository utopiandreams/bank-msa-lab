package lab.bank.transfer.application.port.out

import lab.bank.transfer.domain.InterbankTransfer
import lab.bank.transfer.domain.Transfer

/**
 * 송금 이벤트 발행 포트 (아웃바운드). 구현: infrastructure/persistence/OutboxTransferEventPublisher (Transactional Outbox, 6주 차).
 *
 * 계약 (6주 차에 바뀜):
 * - 송금을 끝 상태로 저장하는 **그 트랜잭션 안에서** 부른다. 구현은 Kafka로 바로 보내지 않고 outbox 테이블에 적기만 한다.
 *   Kafka 전송은 릴레이가 커밋 뒤에 따로 한다 → 커밋 직후 죽어도 보낼 이벤트가 DB에 남는다.
 * - 실패하면 **예외를 던진다** → 송금 상태 변경도 함께 롤백된다. "송금은 끝났는데 이벤트는 없음"을 만들지 않기 위해서다.
 *   잡아서 삼키면 유실 구멍이 다시 열린다 (try-catch 금지).
 *   롤백되면 송금은 의도 상태(PROCESSING 등)에 남는다 = 결과 불명과 같은 처리 (같은 송금 ID로 재시도, core가 멱등).
 *
 * 이전 계약 (4~5주 차): 커밋 **뒤** 트랜잭션 밖에서 Kafka로 바로 보내고, 실패해도 예외를 던지지 않았다
 * (알림 때문에 송금 결과를 실패로 만들 수 없어서). 그 대가가 커밋과 발행 사이의 유실이었다 (TransferEventLossTest로 재현).
 */
interface TransferEventPublisher {

    /** 송금이 끝 상태(COMPLETED·FAILED)로 바뀌었을 때 호출한다 */
    fun publishFinished(transfer: Transfer)

    /** 타행 송금이 끝 상태(COMPLETED·FAILED·CANCELLED)로 바뀌었을 때 호출한다 (ADR-0004). 같은 이벤트(TransferFinishedEvent)로 나간다 */
    fun publishFinished(transfer: InterbankTransfer)
}
