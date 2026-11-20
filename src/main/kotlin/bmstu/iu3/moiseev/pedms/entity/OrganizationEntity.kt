package bmstu.iu3.moiseev.pedms.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Организация — юридическое лицо, в рамках которого ведётся
 * кадровый документооборот.
 *
 * Уровень данных (раздел 3.7 записки). В системе может быть несколько
 * организаций; каждое подразделение относится ровно к одной из них.
 */
@Entity
@Table(name = "organization")
class OrganizationEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "org_id")
    var id: Int? = null,

    @Column(name = "name", nullable = false, length = 200)
    var name: String = "",

    /** ИНН организации. */
    @Column(name = "inn", length = 12)
    var inn: String? = null,

    /** КПП организации. */
    @Column(name = "kpp", length = 10)
    var kpp: String? = null,

    @Column(name = "legal_address", length = 500)
    var legalAddress: String? = null,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
)
