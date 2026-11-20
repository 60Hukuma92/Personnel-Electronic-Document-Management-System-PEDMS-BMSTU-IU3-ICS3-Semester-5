package bmstu.iu3.moiseev.pedms.integration

import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.domain.UserStatus
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DepartmentEntity
import bmstu.iu3.moiseev.pedms.entity.OrganizationEntity
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.repository.OrganizationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

@DataJpaTest
class RepositoryIntegrationTests {

    @Autowired lateinit var organizationRepository: OrganizationRepository
    @Autowired lateinit var departmentRepository: DepartmentRepository
    @Autowired lateinit var appUserRepository: AppUserRepository

    @Test
    fun `organization department and user are persisted together`() {
        val organization = organizationRepository.save(
            OrganizationEntity(name = "МГТУ им. Н.Э. Баумана", inn = "770200000000")
        )
        val department = departmentRepository.save(
            DepartmentEntity(
                organization = organization,
                name = "Кафедра ИУ3",
                code = "IU3",
                level = 2,
                path = "/1/"
            )
        )
        val user = appUserRepository.save(
            AppUserEntity(
                department = department,
                personnelNumber = "IU3-00001",
                fullName = "Иванов Иван Иванович",
                email = "ivanov@pedms.ru",
                role = UserRole.EMPLOYEE,
                status = UserStatus.ACTIVE,
                passwordHash = "{bcrypt}test"
            )
        )

        val loaded = appUserRepository.findByEmailIgnoreCase("ivanov@pedms.ru")

        assertNotNull(user.id)
        assertEquals("Кафедра ИУ3", loaded?.department?.name)
        assertEquals(organization.id, loaded?.department?.organization?.id)
    }
}
