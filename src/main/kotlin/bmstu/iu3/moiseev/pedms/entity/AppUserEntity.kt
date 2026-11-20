package bmstu.iu3.moiseev.pedms.entity

import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.domain.UserStatus
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
 * Учётная запись пользователя (FR190).
 *
 * Один человек — одна учётная запись. Уникальны ИНН, СНИЛС, адрес
 * электронной почты, телефон и табельный номер.
 *
 * Пароль хранится только в виде хэш-кода BCrypt (TR030).
 *
 * Уровень данных (раздел 3.7 записки).
 */
@Entity
@Table(name = "app_users")
class AppUserEntity(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    var id: Int? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dept_id")
    var department: DepartmentEntity? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id")
    var position: PositionEntity? = null,

    /** Табельный номер. */
    @Column(name = "personnel_number", length = 20, unique = true)
    var personnelNumber: String? = null,

    /** Фамилия, имя, отчество. */
    @Column(name = "full_name", nullable = false, length = 150)
    var fullName: String = "",

    @Column(name = "date_of_birth")
    var dateOfBirth: LocalDate? = null,

    @Column(name = "inn", length = 12, unique = true)
    var inn: String? = null,

    @Column(name = "snils", length = 14, unique = true)
    var snils: String? = null,

    /** Телефон в формате +7 XXX XXX-XX-XX. */
    @Column(name = "phone", length = 20, unique = true)
    var phone: String? = null,

    /** Адрес электронной почты; используется как логин. */
    @Column(name = "email", nullable = false, unique = true, length = 254)
    var email: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    var role: UserRole = UserRole.EMPLOYEE,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: UserStatus = UserStatus.CREATED,

    /** Хэш пароля (BCrypt). Открытый пароль в базе не хранится. */
    @Column(name = "password_hash", nullable = false, length = 100)
    var passwordHash: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "last_login")
    var lastLogin: LocalDateTime? = null
) {
    /** Признак того, что пользователь может войти в систему. */
    val canLogin: Boolean
        get() = status.isActive
}
