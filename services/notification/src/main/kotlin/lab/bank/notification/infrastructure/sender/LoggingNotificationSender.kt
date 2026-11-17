package lab.bank.notification.infrastructure.sender

import lab.bank.notification.application.NotificationSender
import lab.bank.notification.domain.Notification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 가짜 발송기: 로그만 남긴다 (ADR-0003 결정 6, 최소 범위) */
@Component
class LoggingNotificationSender : NotificationSender {

    override fun send(notification: Notification) {
        log.info("[가짜 발송] to={}, transferId={}, message={}", notification.recipientId, notification.transferId, notification.message)
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoggingNotificationSender::class.java)
    }
}
