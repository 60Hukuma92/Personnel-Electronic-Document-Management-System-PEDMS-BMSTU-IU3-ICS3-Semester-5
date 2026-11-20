package bmstu.iu3.moiseev.pedms.entity

import bmstu.iu3.moiseev.pedms.domain.AudienceType
import bmstu.iu3.moiseev.pedms.domain.SheetStatus
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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Лист ознакомления с ЛНА или приказом (FR110).
 *
 * Создаётся сотрудником отдела кадров для выбранной аудитории:
 * вся организация, подразделение, должность или перечень сотрудников.
 *
 * Счётчики totalExpected и acknowledgedCount поддерживаются триггером
 * trg_sheet_counters — они пересчитываются при любом изменении отметок.
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "acquaintance_sheet")
class AcquaintanceSheetEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sheet_id")
    var id: Int? = null,

    /** Документ (ЛНА или приказ), с которым знакомятся работники. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doc_id", nullable = false)
    var document: DocumentEntity? = null,

    /** Сотрудник ОК, создавший лист. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    var createdBy: AppUserEntity? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", nullable = false, length = 20)
    var audienceType: AudienceType = AudienceType.ALL,

    /**
     * Уточнение аудитории в виде строки:
     * идентификатор подразделения, идентификатор должности
     * или список идентификаторов сотрудников через запятую.
     */
    @Column(name = "audience_ref", length = 200)
    var audienceRef: String? = null,

    /** Срок ознакомления. */
    @Column(name = "deadline")
    var deadline: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: SheetStatus = SheetStatus.ACTIVE,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    /** Сколько сотрудников должно ознакомиться. Пересчитывается триггером. */
    @Column(name = "total_expected", nullable = false)
    var totalExpected: Int = 0,

    /** Сколько сотрудников уже ознакомились. Пересчитывается триггером. */
    @Column(name = "acknowledged_count", nullable = false)
    var acknowledgedCount: Int = 0
) {
    /** Процент ознакомления по состоянию на текущий момент. */
    val percentComplete: Int
        get() = if (totalExpected == 0) 0 else acknowledgedCount * 100 / totalExpected
}
