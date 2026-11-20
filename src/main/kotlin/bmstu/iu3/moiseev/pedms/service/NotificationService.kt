package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.NotificationChannel
import bmstu.iu3.moiseev.pedms.domain.NotificationEvent
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.entity.NotificationEntity
import bmstu.iu3.moiseev.pedms.entity.UserSettingsEntity
import bmstu.iu3.moiseev.pedms.repository.NotificationRepository
import bmstu.iu3.moiseev.pedms.repository.UserSettingsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userSettingsRepository: UserSettingsRepository
) {
    @Transactional
    fun notify(
        user: AppUserEntity,
        event: NotificationEvent,
        document: DocumentEntity? = null,
        body: String
    ): List<NotificationEntity> {
        val userId = user.id ?: return emptyList()
        val settings = userSettingsRepository.findByUserId(userId)
            ?: userSettingsRepository.save(UserSettingsEntity(userId = userId))
        if (!eventEnabled(settings, event)) return emptyList()
        return enabledChannels(settings).map { channel ->
            notificationRepository.save(
                NotificationEntity(
                    user = user,
                    document = document,
                    eventType = event,
                    channel = channel,
                    body = body
                )
            )
        }
    }

    fun notifyIfMissing(
        user: AppUserEntity,
        event: NotificationEvent,
        document: DocumentEntity?,
        body: String
    ): List<NotificationEntity> {
        val userId = user.id ?: return emptyList()
        val documentId = document?.id
        if (notificationRepository.findAllByUserIdAndEventTypeAndDocumentId(userId, event, documentId).isNotEmpty()) {
            return emptyList()
        }
        return notify(user, event, document, body)
    }

    fun eventEnabled(settings: UserSettingsEntity, event: NotificationEvent): Boolean {
        val configured = settings.notifEventTypes.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        return configured.isEmpty() || event.name in configured
    }

    fun enabledChannels(settings: UserSettingsEntity): List<NotificationChannel> =
        buildList {
            if (settings.notifWebEnabled) add(NotificationChannel.WEB)
            if (settings.notifEmailEnabled) add(NotificationChannel.EMAIL)
        }
}
