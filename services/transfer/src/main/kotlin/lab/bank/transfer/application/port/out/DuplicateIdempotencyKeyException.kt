package lab.bank.transfer.application.port.out

import lab.bank.transfer.domain.IdempotencyKey

/**
 * 저장 포트 계약: 같은 고객 멱등 키의 송금이 이미 저장돼 있어 INSERT가 막혔다 (같은 키 동시 요청에서 늦은 쪽).
 *
 * 어댑터가 DB의 UNIQUE 위반(DataIntegrityViolationException)을 이 예외로 번역한다:
 * - use case가 Spring·JPA 예외를 몰라도 되게 (헥사고날 경계)
 * - 다른 제약 위반(금액 CHECK 등)과 섞이지 않게: **제약 이름**(uq_transfer_idempotency_key)으로만 골라낸다
 *   (core에서 65자 키의 길이 위반이 중복 키 재시도에 잘못 잡힌 버그의 교훈)
 *
 * 고객에게 나가는 예외가 아니다 (TransferException 아님). use case가 잡아서 기존 송금을 읽어 처리한다.
 */
class DuplicateIdempotencyKeyException(
    val idempotencyKey: IdempotencyKey,
    cause: Throwable,
) : RuntimeException("같은 멱등 키의 송금이 이미 있음: ${idempotencyKey.value}", cause)
