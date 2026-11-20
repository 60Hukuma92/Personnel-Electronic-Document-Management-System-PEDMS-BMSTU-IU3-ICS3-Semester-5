package bmstu.iu3.moiseev.pedms.entity

import bmstu.iu3.moiseev.pedms.domain.MarkStatus
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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * Отметка об ознакомлении (FR115, FR220).
 *
 * На одного сотрудника в листе ознакомления приходится ровно одна отметка
 * (ограничение уникальности пары sheet_id + user_id).
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "acquaintance_mark")
class AcquaintanceMarkEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mark_id")
    var id: Int? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sheet_id", nullable = false)
    var sheet: AcquaintanceSheetEntity? = null,

    /** Сотрудник, который должен ознакомиться с документом. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: AppUserEntity? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: MarkStatus = MarkStatus.PENDING,

    /** Дата и время подтверждения ознакомления. */
    @Column(name = "read_at")
    var readAt: LocalDateTime? = null,

    /** Время последнего отправленного напоминания. */
    @Column(name = "reminder_sent_at")
    var reminderSentAt: LocalDateTime? = null,

    /**
     * Данные простой электронной подписи: код подтверждения из SMS,
     * дата подтверждения, номер телефона.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signature_data", columnDefinition = "jsonb")
    var signatureData: Map<String, Any?>? = null,

    /** ВЫЧИСЛЯЕМОЕ ПОЛЕ БАЗЫ ДАННЫХ: признак подтверждённого ознакомления. */
    @Column(name = "is_acknowledged", insertable = false, updatable = false)
    var isAcknowledged: Boolean? = null
)
