package bmstu.iu3.moiseev.pedms.entity

import bmstu.iu3.moiseev.pedms.domain.DocStatus
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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Кадровый документ — центральная сущность системы (FR200).
 *
 * Частные случаи: заявление (APPLICATION), приказ (ORDER), локальный
 * нормативный акт (LNA). Заявление может быть документом-основанием
 * для приказа — ссылка хранится в поле baseDocId.
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "document")
class DocumentEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doc_id")
    var id: Int? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    var type: DocTypeEntity? = null,

    /** Автор документа. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    var author: AppUserEntity? = null,

    /** Документ-основание (для приказа — заявление). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "base_doc_id")
    var baseDoc: DocumentEntity? = null,

    /**
     * Регистрационный номер.
     *
     * Присваивается автоматически триггером trg_assign_document_number,
     * если значение не задано явно.
     */
    @Column(name = "number", unique = true, length = 30)
    var number: String? = null,

    @Column(name = "title", nullable = false, length = 300)
    var title: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: DocStatus = DocStatus.DRAFT,

    /**
     * Значения полей формы документа в формате JSON.
     *
     * Структура проверяется сервисом ValidationService по шаблону
     * templateJson вида документа.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "jsonb")
    var contentJson: Map<String, Any?> = emptyMap(),

    /** Ссылка на файл документа во внешнем хранилище. */
    @Column(name = "content_url", length = 500)
    var contentUrl: String? = null,

    /** Начало периода, указанного в полях формы (даты отпуска и т. п.). */
    @Column(name = "period_start")
    var periodStart: LocalDate? = null,

    /** Окончание периода. */
    @Column(name = "period_end")
    var periodEnd: LocalDate? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    /**
     * ВЫЧИСЛЯЕМОЕ ПОЛЕ БАЗЫ ДАННЫХ: число календарных дней периода.
     *
     * Значение вычисляется генератором GENERATED ALWAYS AS ... STORED,
     * поэтому приложение его только читает.
     */
    @Column(name = "vacation_days", insertable = false, updatable = false)
    var vacationDays: Int? = null,

    /** ВЫЧИСЛЯЕМОЕ ПОЛЕ БАЗЫ ДАННЫХ: признак подписанного документа. */
    @Column(name = "is_signed", insertable = false, updatable = false)
    var isSigned: Boolean? = null,

    /** ВЫЧИСЛЯЕМОЕ ПОЛЕ БАЗЫ ДАННЫХ: признак документа в архиве. */
    @Column(name = "is_archived", insertable = false, updatable = false)
    var isArchived: Boolean? = null
)
