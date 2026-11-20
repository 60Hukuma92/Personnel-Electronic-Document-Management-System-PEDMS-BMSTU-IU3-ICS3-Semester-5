package bmstu.iu3.moiseev.pedms.repository

import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.domain.UserStatus
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DelegationEntity
import bmstu.iu3.moiseev.pedms.entity.UserSettingsEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * Учётные записи пользователей.
 *
 * Адрес электронной почты используется как логин (UC030).
 */
@Repository
interface AppUserRepository : JpaRepository<AppUserEntity, Int> {

    fun findByEmailIgnoreCase(email: String): AppUserEntity?

    fun findByPersonnelNumber(personnelNumber: String): AppUserEntity?

    fun existsByEmailIgnoreCase(email: String): Boolean

    fun existsByPersonnelNumber(personnelNumber: String): Boolean

    fun existsByInn(inn: String): Boolean

    fun existsBySnils(snils: String): Boolean

    fun existsByPhone(phone: String): Boolean

    fun findByInn(inn: String): AppUserEntity?

    fun findBySnils(snils: String): AppUserEntity?

    fun findByPhone(phone: String): AppUserEntity?

    fun countByStatus(status: UserStatus): Long

    fun countByRole(role: UserRole): Long

    fun findAllByOrderByFullNameAsc(): List<AppUserEntity>

    fun findAllByStatusOrderByFullNameAsc(status: UserStatus): List<AppUserEntity>

    fun findAllByRoleOrderByFullNameAsc(role: UserRole): List<AppUserEntity>

    fun findAllByDepartmentIdOrderByFullNameAsc(deptId: Int): List<AppUserEntity>

    fun findAllByDepartmentIdAndStatusOrderByFullNameAsc(
        deptId: Int,
        status: UserStatus
    ): List<AppUserEntity>

    fun findAllByPositionIdAndStatusOrderByFullNameAsc(
        positionId: Int,
        status: UserStatus
    ): List<AppUserEntity>

    /**
     * Сотрудники подразделения с указанной должностью.
     * Используется при построении маршрута согласования по правилам.
     */
    @Query(
        """
        select u from AppUserEntity u
        where u.department.id = :deptId
          and u.position.id = :positionId
          and u.status = 'ACTIVE'
        order by u.fullName
        """
    )
    fun findActiveByDepartmentAndPosition(deptId: Int, positionId: Int): List<AppUserEntity>

    /** Сотрудники подразделения с указанной должностью, включая сам отдел и подчинённые. */
    @Query(
        """
        select u from AppUserEntity u
        where u.department.path like concat(:pathPrefix, '%')
          and u.position.id = :positionId
          and u.status = 'ACTIVE'
        order by u.fullName
        """
    )
    fun findActiveByPathAndPosition(pathPrefix: String, positionId: Int): List<AppUserEntity>

    /** Руководители подразделения (для маршрута согласования). */
    @Query(
        """
        select u from AppUserEntity u
        where u.department.id = :deptId
          and u.role = 'MANAGER'
          and u.status = 'ACTIVE'
        order by u.fullName
        """
    )
    fun findManagersOf(deptId: Int): List<AppUserEntity>

    /** Сотрудники отдела кадров. */
    @Query(
        """
        select u from AppUserEntity u
        where u.role in ('HR', 'ADMIN')
          and u.status = 'ACTIVE'
        order by u.fullName
        """
    )
    fun findHrStaff(): List<AppUserEntity>
}

/** Персональные настройки пользователя. */
@Repository
interface UserSettingsRepository : JpaRepository<UserSettingsEntity, Int> {
    fun findByUserId(userId: Int): UserSettingsEntity?
}

/** Делегирование полномочий. */
@Repository
interface DelegationRepository : JpaRepository<DelegationEntity, Int> {

    @Query(
        """
        select d from DelegationEntity d
        where d.fromUser.id = :userId
          and d.dateFrom <= :date
          and d.dateTo >= :date
        """
    )
    fun findActiveDelegationsOf(userId: Int, date: java.time.LocalDate): List<DelegationEntity>
}
