package bmstu.iu3.moiseev.pedms.entity

import bmstu.iu3.moiseev.pedms.domain.NotificationChannel
import bmstu.iu3.moiseev.pedms.domain.NotificationEvent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Уведомление пользователя о событии (FR130, FR135).
 *
 * Уведомление показывается в веб-интерфейсе; пустое поле readAt
 * означает, что уведомление ещё не прочитано.
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "notification")
class NotificationEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notif_id")
    var id: Int? = null,

    /** Получатель уведомления. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: AppUserEntity? = null,

    /** Документ, к которому относится событие. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doc_id")
    var document: DocumentEntity? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    var eventType: NotificationEvent = NotificationEvent.DOCUMENT_ARRIVED,

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    var channel: NotificationChannel = NotificationChannel.WEB,

    @Column(name = "body", nullable = false, length = 1000)
    var body: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    /** Время прочтения уведомления. */
    @Column(name = "read_at")
    var readAt: LocalDateTime? = null
) {
    /** Признак того, что уведомление ещё не прочитано. */
    val isUnread: Boolean
        get() = readAt == null
}
