package bmstu.iu3.moiseev.pedms.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Должность — элемент справочника должностей.
 *
 * По этому справочнику строятся правила маршрутизации (FR050):
 * для вида документа, подразделения и должности инициатора определяется
 * должность согласующего.
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "position")
class PositionEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "position_id")
    var id: Int? = null,

    @Column(name = "name", nullable = false, unique = true, length = 200)
    var name: String = ""
)
