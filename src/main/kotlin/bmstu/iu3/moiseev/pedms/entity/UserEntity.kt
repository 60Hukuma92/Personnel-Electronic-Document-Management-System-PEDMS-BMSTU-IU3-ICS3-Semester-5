package bmstu.iu3.moiseev.pedms.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "app_users")
class UserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "full_name", nullable = false, length = 150)
    var fullName: String = "",

    @Column(nullable = false, unique = true)
    var email: String = "",

    @Column(nullable = false)
    var role: String = "EMPLOYEE",

    @Column(nullable = false)
    var status: String = "ACTIVE",

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
)