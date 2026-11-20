package bmstu.iu3.moiseev.pedms.entity

import bmstu.iu3.moiseev.pedms.domain.StepAction
import bmstu.iu3.moiseev.pedms.domain.StepStatus
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
 * Этап маршрута согласования документа (FR210).
 *
 * Этапы выполняются по порядку, кроме параллельных (isParallel).
 * При отклонении маршрут останавливается. Делегирование сохраняется
 * в поле delegatedFrom, чтобы было видно, у кого исходно был этап.
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "route_step")
class RouteStepEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "step_id")
    var id: Int? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doc_id", nullable = false)
    var document: DocumentEntity? = null,

    /** Согласующий, которому назначен этап. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: AppUserEntity? = null,

    /** Пользователь, от которого этап был делегирован. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delegated_from")
    var delegatedFrom: AppUserEntity? = null,

    @Column(name = "step_order", nullable = false)
    var stepOrder: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    var action: StepAction = StepAction.APPROVE,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: StepStatus = StepStatus.PENDING,

    /** Комментарий участника маршрута. */
    @Column(name = "comment", length = 1000)
    var comment: String? = null,

    /** Дата и время принятия решения. */
    @Column(name = "decided_at")
    var decidedAt: LocalDateTime? = null,

    /** Срок исполнения этапа. */
    @Column(name = "deadline")
    var deadline: LocalDateTime? = null,

    /** Признак параллельного этапа. */
    @Column(name = "is_parallel", nullable = false)
    var isParallel: Boolean = false,

    /**
     * Данные простой электронной подписи: код подтверждения из SMS,
     * дата подтверждения, номер телефона.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signature_data", columnDefinition = "jsonb")
    var signatureData: Map<String, Any?>? = null
)
