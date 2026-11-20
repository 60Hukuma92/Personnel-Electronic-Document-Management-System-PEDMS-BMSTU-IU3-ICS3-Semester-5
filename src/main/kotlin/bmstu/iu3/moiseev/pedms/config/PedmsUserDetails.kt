package bmstu.iu3.moiseev.pedms.config

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Учётные данные пользователя ИС КЭДО.
 *
 * В отличие от стандартного [org.springframework.security.core.userdetails.User],
 * объект хранит идентификатор пользователя — это позволяет сервисам
 * загружать полную карточку сотрудника без повторного поиска по логину.
 */
class PedmsUserDetails(
    private val userId: Int,
    private val email: String,
    private val fullName: String,
    private val passwordHash: String,
    private val role: String,
    private val enabled: Boolean
) : UserDetails {

    override fun getAuthorities(): Collection<GrantedAuthority> = listOf(GrantedAuthority { "ROLE_$role" })

    override fun getPassword(): String = passwordHash

    override fun getUsername(): String = email

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = true

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = enabled

    /** Фамилия, имя, отчество — показывается в шапке интерфейса. */
    val displayName: String get() = fullName

    /** Идентификатор пользователя в базе данных. */
    val id: Int get() = userId
}
