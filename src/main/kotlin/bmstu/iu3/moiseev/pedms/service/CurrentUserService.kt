package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.config.PedmsUserDetails
import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

/**
 * Доступ к учётной записи текущего пользователя.
 *
 * Идентификатор пользователя хранится в сессии (в объекте PedmsUserDetails),
 * поэтому карточка сотрудника загружается из базы по идентификатору.
 */
@Service
class CurrentUserService(private val userRepository: AppUserRepository) {

    /** Текущий пользователь или null, если пользователь не авторизован. */
    fun currentUser(): AppUserEntity? {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? PedmsUserDetails
            ?: return null
        return userRepository.findById(principal.id).orElse(null)
    }

    /** Текущий пользователь; при отсутствии авторизации — отказ в доступе. */
    fun requireCurrentUser(): AppUserEntity =
        currentUser() ?: throw AccessDeniedException("Пользователь не авторизован")

    /** Роль текущего пользователя. */
    fun currentRole(): UserRole? = currentUser()?.role

    /**
     * Проверка наличия требуемой роли.
     * Роли в системе иерархические: ADMIN подразумевает права HR,
     * HR подразумевает права MANAGER.
     */
    fun hasRole(required: UserRole): Boolean {
        val role = currentRole() ?: return false
        return when (required) {
            UserRole.EMPLOYEE -> true
            UserRole.MANAGER -> role.isAtLeastManager
            UserRole.HR -> role.isAtLeastHr
            UserRole.ADMIN -> role == UserRole.ADMIN
        }
    }
}
