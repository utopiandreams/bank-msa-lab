package lab.bank.notification

import lab.bank.notification.application.NotificationSender
import lab.bank.notification.domain.Notification
import java.util.concurrent.CopyOnWriteArrayList

/** 발송 횟수를 센다: "같은 이벤트 두 번 → 발송 한 번"을 확인하려고 (리스너 스레드에서 호출되므로 스레드 안전 리스트) */
class RecordingNotificationSender : NotificationSender {
    val sent = CopyOnWriteArrayList<Notification>()
    override fun send(notification: Notification) {
        sent += notification
    }
}
