package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.NotificationChannel
import bmstu.iu3.moiseev.pedms.domain.NotificationEvent
import bmstu.iu3.moiseev.pedms.domain.UserStatus
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.NotificationEntity
import bmstu.iu3.moiseev.pedms.entity.UserSettingsEntity
import bmstu.iu3.moiseev.pedms.repository.NotificationRepository
import bmstu.iu3.moiseev.pedms.repository.UserSettingsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

class NotificationServiceTests {
    @Test
    fun `notification respects event and channel settings`() {
        val notifications = mock(NotificationRepository::class.java)
        val settings = mock(UserSettingsRepository::class.java)
        val user = AppUserEntity(id = 7, fullName = "Иван", email = "ivan@test", status = UserStatus.ACTIVE)
        `when`(settings.findByUserId(7)).thenReturn(
            UserSettingsEntity(
                userId = 7,
                notifWebEnabled = false,
                notifEmailEnabled = true,
                notifEventTypes = NotificationEvent.DOCUMENT_SIGNED.name
            )
        )
        `when`(notifications.save(any(NotificationEntity::class.java))).thenAnswer { it.arguments[0] }
        val service = NotificationService(notifications, settings)

        val sent = service.notify(user, NotificationEvent.DOCUMENT_SIGNED, body = "Подписан")
        val skipped = service.notify(user, NotificationEvent.DOCUMENT_APPROVED, body = "Согласован")

        assertEquals(1, sent.size)
        assertEquals(NotificationChannel.EMAIL, sent.single().channel)
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `missing settings enable web notifications by default`() {
        val notifications = mock(NotificationRepository::class.java)
        val settings = mock(UserSettingsRepository::class.java)
        val user = AppUserEntity(id = 8, fullName = "Пётр", email = "petr@test", status = UserStatus.ACTIVE)
        `when`(settings.findByUserId(8)).thenReturn(null)
        `when`(settings.save(any(UserSettingsEntity::class.java))).thenAnswer { it.arguments[0] }
        `when`(notifications.save(any(NotificationEntity::class.java))).thenAnswer { it.arguments[0] }
        val service = NotificationService(notifications, settings)

        val sent = service.notify(user, NotificationEvent.COMMENT_ADDED, body = "Комментарий")

        assertEquals(NotificationChannel.WEB, sent.single().channel)
    }
}
