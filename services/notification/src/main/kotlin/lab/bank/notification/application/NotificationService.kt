package lab.bank.notification.application

import lab.bank.common.event.TransferFinishedEvent
import lab.bank.notification.domain.Notification
import lab.bank.notification.infrastructure.persistence.NotificationRepository
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * 송금 끝 이벤트 → 알림 (멱등 컨슈머, ADR-0003 결정 5).
 *
 *   ① 알림을 PENDING으로 INSERT (트랜잭션 1) ← event_id UNIQUE가 중복을 막는다
 *   ② 발송 (트랜잭션 밖: 외부 호출 중에 DB 커넥션을 쥐지 않는다, pitfalls 8 / transfer T-1과 같은 패턴)
 *   ③ SENT로 커밋 (트랜잭션 2)
 *
 * 중복 판별을 "먼저 조회해 보고 없으면 INSERT"로 하지 않는 이유: 같은 이벤트가 동시에 두 번 오면 둘 다 "없음"을 본다.
 * INSERT를 시도하고 UNIQUE 위반을 잡는 게 원자적이다 (core·transfer의 멱등 키와 같은 결론).
 *
 * ⚠️ 알려진 한계 (15주 차): ①과 ③ 사이에 죽으면 PENDING에 멈춘다. 재시작 후 같은 이벤트가 다시 와도 ①에서 "이미 있음"으로
 *    건너뛰므로 다시 보내지 않는다. 그렇다고 PENDING이면 다시 보내게 하면, 실제로는 보냈는데 ③ 직전에 죽은 경우 두 번 간다
 *    (외부 발송의 결과 불명). → 재발송 스케줄러 + 발송 업체 쪽 멱등 키로 해결한다.
 */
@Service
class NotificationService(
    private val transactionTemplate: TransactionTemplate,
    private val repository: NotificationRepository,
    private val sender: NotificationSender,
) {

    fun handle(event: TransferFinishedEvent): Outcome {
        val pending = try {
            transactionTemplate.execute { repository.saveAndFlush(Notification.from(event)) }!!
        } catch (e: DataIntegrityViolationException) {
            if (violatedConstraint(e).equals(EVENT_ID_CONSTRAINT, ignoreCase = true)) {
                log.info("중복 이벤트, 건너뜀: eventId={}, transferId={}", event.eventId, event.transferId)
                return Outcome.DUPLICATE
            }
            throw e   // 다른 제약 위반은 우리 버그
        }

        sender.send(pending)

        transactionTemplate.execute { repository.findById(pending.id).orElseThrow().markSent() }   // 더티 체킹으로 UPDATE
        return Outcome.SENT
    }

    enum class Outcome { SENT, DUPLICATE }

    private fun violatedConstraint(e: DataIntegrityViolationException): String? =
        generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .firstOrNull()
            ?.constraintName

    companion object {
        private const val EVENT_ID_CONSTRAINT = "uq_notification_event_id"
        private val log = LoggerFactory.getLogger(NotificationService::class.java)
    }
}
