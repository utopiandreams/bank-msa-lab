package lab.bank.notification.application

import lab.bank.notification.domain.Notification

/**
 * 알림 발송 (외부 연동 자리). 지금 구현은 가짜(infrastructure/sender/LoggingNotificationSender).
 * 15주 차: 지연·실패를 주입하는 가짜 발송 업체로 바꾸고, 타임아웃·결과 불명·재시도를 다룬다.
 */
interface NotificationSender {
    fun send(notification: Notification)
}
