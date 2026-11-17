package lab.bank.notification.infrastructure.persistence

import lab.bank.notification.domain.Notification
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationRepository : JpaRepository<Notification, UUID> {

    fun findByEventId(eventId: UUID): Notification?
}
