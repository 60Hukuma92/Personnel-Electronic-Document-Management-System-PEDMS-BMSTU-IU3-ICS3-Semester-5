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
import java.time.LocalDateTime

/**
 * Подразделение — структурная единица организации (департамент, отдел, филиал).
 *
 * Подразделения образуют дерево: родительское подразделение задаётся полем
 * parent_dept_id. Поле path (вида «/1/4/9/») и поле level поддерживаются
 * триггером trg_department_tree на уровне БД.
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "department")
class DepartmentEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dept_id")
    var id: Int? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_id", nullable = false)
    var organization: OrganizationEntity? = null,

    /** Родительское подразделение. NULL — подразделение верхнего уровня. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_dept_id")
    var parent: DepartmentEntity? = null,

    /** Руководитель подразделения. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    var manager: AppUserEntity? = null,

    @Column(name = "name", nullable = false, length = 200)
    var name: String = "",

    /** Код подразделения, уникальный в пределах организации. */
    @Column(name = "code", nullable = false, length = 50)
    var code: String = "",

    /** Уровень в иерархии: 0 — верхний уровень. Заполняется триггером. */
    @Column(name = "level", nullable = false)
    var level: Int = 0,

    /** Путь в иерархии вида /1/4/9/. Заполняется триггером. */
    @Column(name = "path", nullable = false, length = 500)
    var path: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    /** Дата ликвидации подразделения. */
    @Column(name = "liquidated_at")
    var liquidatedAt: LocalDateTime? = null
) {
    /** Признак того, что подразделение действует. */
    val isActive: Boolean
        get() = liquidatedAt == null
}
