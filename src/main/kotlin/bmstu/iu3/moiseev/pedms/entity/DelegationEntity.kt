package bmstu.iu3.moiseev.pedms.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Делегирование полномочий (FR070, UC060).
 *
 * На время действия делегирования документы, адресованные пользователю
 * fromUser, направляются его заместителю toUser.
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "delegation")
class DelegationEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "delegation_id")
    var id: Int? = null,

    /** Пользователь, передающий свои полномочия. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_user_id", nullable = false)
    var fromUser: AppUserEntity? = null,

    /** Пользователь, получающий полномочия. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_user_id", nullable = false)
    var toUser: AppUserEntity? = null,

    @Column(name = "date_from", nullable = false)
    var dateFrom: LocalDate = LocalDate.now(),

    @Column(name = "date_to", nullable = false)
    var dateTo: LocalDate = LocalDate.now(),

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
) {
    /** Признак того, что делегирование действует на указанную дату. */
    fun isActiveOn(date: LocalDate = LocalDate.now()): Boolean =
        !date.isBefore(dateFrom) && !date.isAfter(dateTo)
}
