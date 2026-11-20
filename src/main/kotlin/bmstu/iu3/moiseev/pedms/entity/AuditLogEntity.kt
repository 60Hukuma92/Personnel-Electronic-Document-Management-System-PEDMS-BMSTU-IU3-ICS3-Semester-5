package bmstu.iu3.moiseev.pedms.entity

import bmstu.iu3.moiseev.pedms.domain.AuditAction
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
import jakarta.persistence.PreRemove
import jakarta.persistence.PreUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/**
 * Журнал аудита — история действий над документами (FR080).
 *
 * Записи только добавляются: изменение и удаление запрещены триггером
 * trg_audit_immutable на уровне базы данных (TR040).
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "audit_log")
class AuditLogEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    var id: Int? = null,

    /** Документ, над которым выполнено действие. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doc_id")
    var document: DocumentEntity? = null,

    /** Пользователь, выполнивший действие. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    var user: AppUserEntity? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40)
    var action: AuditAction = AuditAction.COMMENT,

    /** Комментарий участника маршрута. */
    @Column(name = "comment", length = 1000)
    var comment: String? = null,

    /** Изменённые поля документа в формате JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_fields", columnDefinition = "jsonb")
    var changedFields: Map<String, Any?>? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
) {
    @PreUpdate
    @PreRemove
    fun preventMutation() {
        throw IllegalStateException("Записи журнала аудита нельзя изменять или удалять")
    }
}
