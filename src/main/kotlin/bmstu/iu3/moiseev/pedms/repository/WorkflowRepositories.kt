package bmstu.iu3.moiseev.pedms.repository

import bmstu.iu3.moiseev.pedms.domain.MarkStatus
import bmstu.iu3.moiseev.pedms.domain.NotificationEvent
import bmstu.iu3.moiseev.pedms.entity.AcquaintanceMarkEntity
import bmstu.iu3.moiseev.pedms.entity.AcquaintanceSheetEntity
import bmstu.iu3.moiseev.pedms.entity.AuditLogEntity
import bmstu.iu3.moiseev.pedms.entity.NotificationEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/** Листы ознакомления. */
@Repository
interface AcquaintanceSheetRepository : JpaRepository<AcquaintanceSheetEntity, Int> {
    fun findAllByOrderByCreatedAtDesc(): List<AcquaintanceSheetEntity>
    fun findAllByCreatedByIdOrderByCreatedAtDesc(createdById: Int): List<AcquaintanceSheetEntity>
    fun findAllByDocumentId(documentId: Int): List<AcquaintanceSheetEntity>
}

/** Отметки об ознакомлении. */
@Repository
interface AcquaintanceMarkRepository : JpaRepository<AcquaintanceMarkEntity, Int> {

    fun findAllBySheetIdOrderByIdAsc(sheetId: Int): List<AcquaintanceMarkEntity>

    fun findAllByUserIdAndStatus(userId: Int, status: MarkStatus): List<AcquaintanceMarkEntity>

    fun findBySheetIdAndUserId(sheetId: Int, userId: Int): AcquaintanceMarkEntity?

    /** Отметки, по которым срок ознакомления ещё не истёк. */
    @Query(
        """
        select m from AcquaintanceMarkEntity m
        where m.status = 'PENDING'
          and m.sheet.deadline is not null
          and m.sheet.deadline < :date
        """
    )
    fun findOverdue(date: java.time.LocalDate): List<AcquaintanceMarkEntity>
}

/** Уведомления пользователей. */
@Repository
interface NotificationRepository : JpaRepository<NotificationEntity, Int> {

    fun findAllByUserIdOrderByCreatedAtDesc(userId: Int): List<NotificationEntity>

    @Query(
        """
        select count(n) from NotificationEntity n
        where n.user.id = :userId and n.readAt is null
        """
    )
    fun countUnread(userId: Int): Long

    @Query(
        """
        select n from NotificationEntity n
        where n.user.id = :userId and n.readAt is null
        order by n.createdAt desc
        """
    )
    fun findUnread(userId: Int): List<NotificationEntity>

    /** Уведомления по событию — используется для проверки, что событие уже не отправлялось. */
    fun findAllByUserIdAndEventTypeAndDocumentId(
        userId: Int,
        eventType: NotificationEvent,
        documentId: Int?
    ): List<NotificationEntity>
}

/** Журнал аудита. */
@Repository
interface AuditLogRepository : JpaRepository<AuditLogEntity, Int> {

    fun findAllByDocumentIdOrderByCreatedAtDesc(documentId: Int): List<AuditLogEntity>

    fun findAllByUserIdOrderByCreatedAtDesc(userId: Int): List<AuditLogEntity>

    fun findAllByOrderByCreatedAtDesc(): List<AuditLogEntity>
}
