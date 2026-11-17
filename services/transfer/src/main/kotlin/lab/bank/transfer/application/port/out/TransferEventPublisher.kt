package lab.bank.transfer.application.port.out

import lab.bank.transfer.domain.Transfer

/**
 * 송금 이벤트 발행 포트 (아웃바운드). 구현: infrastructure/kafka/KafkaTransferEventPublisher.
 *
 * 계약: **최선을 다할 뿐, 예외를 던지지 않는다.** 발행이 실패해도 송금 결과(이미 커밋됨)는 바뀌지 않는다.
 * 알림 때문에 고객의 송금 결과를 실패로 만들 수는 없어서다.
 *
 * ⚠️ 의도적으로 남긴 구멍 (ADR-0003, 6주 차 재현 대상): 송금 결과 커밋 **뒤에** 트랜잭션 밖에서 보낸다.
 *    커밋과 발행 사이에 죽거나 발행이 실패하면 이 이벤트는 사라진다 → 6주 차에 재현한 뒤 Transactional Outbox로 막는다.
 */
interface TransferEventPublisher {

    /** 송금이 끝 상태(COMPLETED·FAILED)로 바뀌었을 때 호출한다 */
    fun publishFinished(transfer: Transfer)
}
