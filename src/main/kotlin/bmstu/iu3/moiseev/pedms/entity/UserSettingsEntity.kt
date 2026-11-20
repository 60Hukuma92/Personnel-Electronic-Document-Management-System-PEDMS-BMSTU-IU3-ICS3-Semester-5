package bmstu.iu3.moiseev.pedms.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Персональные настройки пользователя (FR130).
 *
 * Связь с учётной записью — 1 : 1. Настройки определяют, о каких событиях
 * пользователь получает уведомления, а также тему оформления и язык.
 *
 * Первичный ключ user_id совпадает с идентификатором пользователя,
 * поэтому настройки адресуются напрямую по этому ключу.
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "user_settings")
class UserSettingsEntity(

    @Id
    @Column(name = "user_id")
    var userId: Int? = null,

    /** Признак того, что уведомления показываются в веб-интерфейсе. */
    @Column(name = "notif_web_enabled", nullable = false)
    var notifWebEnabled: Boolean = true,

    /**
     * Признак того, что уведомления отправляются по электронной почте.
     * В учебном прототипе канал не используется, но предусмотрен
     * для перспективного развития системы.
     */
    @Column(name = "notif_email_enabled", nullable = false)
    var notifEmailEnabled: Boolean = false,

    /** Тема оформления: light, dark, auto. */
    @Column(name = "theme", nullable = false, length = 20)
    var theme: String = "light",

    /** Язык интерфейса: ru, en. */
    @Column(name = "language", nullable = false, length = 10)
    var language: String = "ru"
)
