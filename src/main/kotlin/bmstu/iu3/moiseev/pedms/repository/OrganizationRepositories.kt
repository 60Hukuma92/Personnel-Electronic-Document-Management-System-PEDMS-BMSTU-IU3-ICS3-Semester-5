package bmstu.iu3.moiseev.pedms.repository

import bmstu.iu3.moiseev.pedms.entity.DepartmentEntity
import bmstu.iu3.moiseev.pedms.entity.OrganizationEntity
import bmstu.iu3.moiseev.pedms.entity.PositionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/** Организации системы. */
@Repository
interface OrganizationRepository : JpaRepository<OrganizationEntity, Int> {
    fun findAllByIsActiveTrue(): List<OrganizationEntity>
    fun findFirstByOrderByIdAsc(): OrganizationEntity?
}

/**
 * Подразделения.
 *
 * Метод [findTree] возвращает подразделения, упорядоченные по пути
 * в иерархии: путь формируется триггером базы данных, поэтому сортировка
 * по нему даёт корректный обход дерева.
 */
@Repository
interface DepartmentRepository : JpaRepository<DepartmentEntity, Int> {
    fun findAllByOrganizationIdOrderByPathAsc(orgId: Int): List<DepartmentEntity>

    fun findByCode(code: String): DepartmentEntity?

    fun findByParentId(parentId: Int): List<DepartmentEntity>

    @Query("select d from DepartmentEntity d where d.organization.id = :orgId order by d.path")
    fun findTree(orgId: Int): List<DepartmentEntity>
}

/** Справочник должностей. */
@Repository
interface PositionRepository : JpaRepository<PositionEntity, Int> {
    fun findByName(name: String): PositionEntity?

    fun findAllByOrderByNameAsc(): List<PositionEntity>
}
