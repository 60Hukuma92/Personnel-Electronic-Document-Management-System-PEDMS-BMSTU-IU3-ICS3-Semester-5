package bmstu.iu3.moiseev.pedms.repository

import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.entity.AttachmentEntity
import bmstu.iu3.moiseev.pedms.entity.DocTypeEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.entity.RouteRuleEntity
import bmstu.iu3.moiseev.pedms.entity.RouteStepEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/** Справочник видов документов. */
@Repository
interface DocTypeRepository : JpaRepository<DocTypeEntity, Int> {
    fun findByCode(code: String): DocTypeEntity?
    fun findAllByIsActiveTrueOrderByNameAsc(): List<DocTypeEntity>
    fun findAllByOrderByNameAsc(): List<DocTypeEntity>
}

/**
 * Документы.
 *
 * Метод [search] реализует список документов с поиском и фильтрами
 * (FR150). Фильтры передаются как параметры; пустое значение означает
 * «без ограничения».
 */
@Repository
interface DocumentRepository : JpaRepository<DocumentEntity, Int> {

    fun findAllByOrderByCreatedAtDesc(): List<DocumentEntity>

    fun findAllByAuthorIdOrderByCreatedAtDesc(authorId: Int): List<DocumentEntity>

    fun findAllByStatusOrderByCreatedAtDesc(status: DocStatus): List<DocumentEntity>

    fun findAllByTypeIdOrderByCreatedAtDesc(typeId: Int): List<DocumentEntity>

    fun findByNumber(number: String): DocumentEntity?

    /**
     * Документы, в которых пользователь является автором, участником
     * маршрута или адресатом листа ознакомления (TR040 — разграничение
     * доступа: пользователь видит только «свои» документы).
     */
    @Query(
        """
        select distinct d from DocumentEntity d
        left join d.baseDoc b
        where d.author.id = :userId
           or b.author.id = :userId
           or exists (
                select s from RouteStepEntity s where s.document.id = d.id and s.user.id = :userId
           )
        order by d.createdAt desc
        """
    )
    fun findAccessibleTo(userId: Int): List<DocumentEntity>

    /** Документы, по которым можно сформировать приказ (FR090). */
    @Query(
        """
        select d from DocumentEntity d
        where d.type.category = 'APPLICATION'
          and d.status in ('APPROVED', 'SIGNED')
          and d.baseDoc is null
        order by d.createdAt desc
        """
    )
    fun findApprovedApplicationsWithoutOrder(): List<DocumentEntity>
}

/** Правила построения маршрута согласования. */
@Repository
interface RouteRuleRepository : JpaRepository<RouteRuleEntity, Int> {

    @Query(
        """
        select r from RouteRuleEntity r
        where r.type.id = :typeId
          and (r.department is null or r.department.id = :deptId)
          and (r.initiatorPosition is null or r.initiatorPosition.id = :positionId)
        order by r.stepOrder asc
        """
    )
    fun findMatching(typeId: Int, deptId: Int?, positionId: Int?): List<RouteRuleEntity>

    fun findAllByTypeIdOrderByStepOrderAsc(typeId: Int): List<RouteRuleEntity>
}

/** Этапы маршрута согласования. */
@Repository
interface RouteStepRepository : JpaRepository<RouteStepEntity, Int> {

    fun findAllByDocumentIdOrderByStepOrderAsc(docId: Int): List<RouteStepEntity>

    fun deleteAllByDocumentId(docId: Int)

    fun findAllByDocumentIdAndUserIdOrderByStepOrderAsc(docId: Int, userId: Int): List<RouteStepEntity>

    @Query(
        """
        select s from RouteStepEntity s
        where s.user.id = :userId
          and s.status in ('PENDING', 'IN_PROGRESS')
        order by s.deadline asc nulls last
        """
    )
    fun findPendingFor(userId: Int): List<RouteStepEntity>

    /** Этапы, срок которых истёк (FR120). */
    @Query(
        """
        select s from RouteStepEntity s
        where s.status in ('PENDING', 'IN_PROGRESS')
          and s.deadline is not null
          and s.deadline < :now
        """
    )
    fun findOverdue(now: java.time.LocalDateTime): List<RouteStepEntity>
}

/** Вложения к документам. */
@Repository
interface AttachmentRepository : JpaRepository<AttachmentEntity, Int> {
    fun findAllByDocumentIdOrderByUploadedAtAsc(docId: Int): List<AttachmentEntity>

    @Query("select coalesce(sum(a.fileSize), 0) from AttachmentEntity a where a.document.id = :docId")
    fun sumFileSize(docId: Int): Long
}
