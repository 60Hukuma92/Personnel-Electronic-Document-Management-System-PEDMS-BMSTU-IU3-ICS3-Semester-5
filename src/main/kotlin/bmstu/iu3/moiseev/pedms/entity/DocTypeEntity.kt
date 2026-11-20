package bmstu.iu3.moiseev.pedms.entity

import bmstu.iu3.moiseev.pedms.domain.DocCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * Справочник видов документов (FR030).
 *
 * Для каждого вида заданы: шаблон формы (templateJson), признаки того,
 * требуется ли согласование, подпись и ознакомление с ЛНА, а также срок
 * хранения в архиве.
 *
 * Отключение вида (isActive = false) запрещает создание новых документов,
 * но не затрагивает уже созданные.
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "doc_type")
class DocTypeEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "type_id")
    var id: Int? = null,

    /** Код вида документа, уникальный в системе. */
    @Column(name = "code", nullable = false, unique = true, length = 20)
    var code: String = "",

    @Column(name = "name", nullable = false, length = 200)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    var category: DocCategory = DocCategory.OTHER,

    /**
     * Описание полей формы документа в формате JSON.
     *
     * Хранится в JSONB сознательно: набор полей зависит от вида документа
     * и меняется через справочник без изменения схемы базы данных.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_json", nullable = false, columnDefinition = "jsonb")
    var templateJson: Map<String, Any?> = emptyMap(),

    /** Признак того, что документ проходит согласование. */
    @Column(name = "requires_approval", nullable = false)
    var requiresApproval: Boolean = true,

    /** Признак того, что документ подписывается. */
    @Column(name = "requires_signature", nullable = false)
    var requiresSignature: Boolean = false,

    /** Тип подписи: NONE, SIMPLE_EP, QUALIFIED_EP. */
    @Column(name = "signature_type", length = 30)
    var signatureType: String? = null,

    /** Признак того, что по документу формируется лист ознакомления. */
    @Column(name = "requires_acquaintance", nullable = false)
    var requiresAcquaintance: Boolean = false,

    /** Срок хранения документа в архиве, лет. */
    @Column(name = "retention_years", nullable = false)
    var retentionYears: Int = 5,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
)
