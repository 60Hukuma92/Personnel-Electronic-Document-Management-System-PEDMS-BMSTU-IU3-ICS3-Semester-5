package bmstu.iu3.moiseev.pedms.entity

import bmstu.iu3.moiseev.pedms.domain.StepAction
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

/**
 * Правило построения маршрута согласования (FR050).
 *
 * Правило описывает: для какого вида документа, в каком подразделении
 * и с какой должности инициатора, кто и в каком порядке согласует документ
 * и в какой срок.
 *
 * Пустые deptId и initiatorPositionId означают «для всех».
 * Правила упорядочиваются по stepOrder, поэтому один и тот же вид
 * документа в разных подразделениях может иметь разные маршруты.
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "route_rule")
class RouteRuleEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    var id: Int? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    var type: DocTypeEntity? = null,

    /** Подразделение инициатора. NULL — «для всех». */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dept_id")
    var department: DepartmentEntity? = null,

    /** Должность инициатора. NULL — «для всех». */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "initiator_position_id")
    var initiatorPosition: PositionEntity? = null,

    /** Должность согласующего. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_position_id")
    var approverPosition: PositionEntity? = null,

    /** Подразделение согласующего. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_dept_id")
    var approverDepartment: DepartmentEntity? = null,

    /** Порядковый номер этапа в маршруте. */
    @Column(name = "step_order", nullable = false)
    var stepOrder: Int = 1,

    /** Действие этапа: APPROVE, SIGN, REVIEW. */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    var action: StepAction = StepAction.APPROVE,

    /** Срок этапа в рабочих днях. */
    @Column(name = "deadline_days", nullable = false)
    var deadlineDays: Int = 3
)
