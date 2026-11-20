package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.DocCategory
import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.domain.MarkStatus
import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.domain.UserStatus
import bmstu.iu3.moiseev.pedms.entity.AcquaintanceMarkEntity
import bmstu.iu3.moiseev.pedms.entity.AcquaintanceSheetEntity
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DocTypeEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.repository.AcquaintanceMarkRepository
import bmstu.iu3.moiseev.pedms.repository.AcquaintanceSheetRepository
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.AuditLogRepository
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import bmstu.iu3.moiseev.pedms.repository.PositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.util.Optional

class AcquaintanceServiceTests {
    private lateinit var sheets: AcquaintanceSheetRepository
    private lateinit var marks: AcquaintanceMarkRepository
    private lateinit var documents: DocumentRepository
    private lateinit var users: AppUserRepository
    private lateinit var departments: DepartmentRepository
    private lateinit var positions: PositionRepository
    private lateinit var audit: AuditLogRepository
    private lateinit var current: CurrentUserService
    private lateinit var notifications: NotificationService
    private lateinit var service: AcquaintanceService
    private lateinit var hr: AppUserEntity
    private lateinit var document: DocumentEntity

    @BeforeEach
    fun setUp() {
        sheets = mock(AcquaintanceSheetRepository::class.java)
        marks = mock(AcquaintanceMarkRepository::class.java)
        documents = mock(DocumentRepository::class.java)
        users = mock(AppUserRepository::class.java)
        departments = mock(DepartmentRepository::class.java)
        positions = mock(PositionRepository::class.java)
        audit = mock(AuditLogRepository::class.java)
        current = mock(CurrentUserService::class.java)
        notifications = mock(NotificationService::class.java)
        hr = AppUserEntity(
            id = 1, fullName = "Кадровик", email = "hr@test",
            role = UserRole.HR, status = UserStatus.ACTIVE
        )
        document = DocumentEntity(
            id = 10,
            type = DocTypeEntity(id = 2, name = "ЛНА", category = DocCategory.LNA),
            author = hr,
            title = "Правила",
            status = DocStatus.SIGNED
        )
        `when`(current.requireCurrentUser()).thenReturn(hr)
        `when`(current.hasRole(UserRole.HR)).thenReturn(true)
        `when`(documents.findById(10)).thenReturn(Optional.of(document))
        `when`(users.findAllByStatusOrderByFullNameAsc(UserStatus.ACTIVE)).thenReturn(listOf(hr))
        `when`(sheets.save(any(AcquaintanceSheetEntity::class.java))).thenAnswer { it.arguments[0] }
        `when`(marks.save(any(AcquaintanceMarkEntity::class.java))).thenAnswer { it.arguments[0] }
        service = AcquaintanceService(
            sheets, marks, documents, users, departments, positions, audit, current, SignatureService(), notifications
        )
    }

    @Test
    fun `create rejects unsigned document`() {
        document.status = DocStatus.DRAFT

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.create(
                bmstu.iu3.moiseev.pedms.dto.AcquaintanceSheetForm(
                    documentId = 10,
                    deadline = LocalDate.now().plusDays(3)
                )
            )
        }

        assertEquals("Лист можно создать только для подписанного документа", error.message)
    }

    @Test
    fun `acknowledgement is recorded once with signature data`() {
        val mark = AcquaintanceMarkEntity(
            id = 5,
            sheet = AcquaintanceSheetEntity(id = 8, document = document, createdBy = hr),
            user = hr
        )
        `when`(marks.findBySheetIdAndUserId(8, 1)).thenReturn(mark)
        `when`(marks.save(any(AcquaintanceMarkEntity::class.java))).thenAnswer { it.arguments[0] }

        service.acknowledge(8, "123456", "123456")

        assertEquals(MarkStatus.ACKNOWLEDGED, mark.status)
        assertEquals("123456", mark.signatureData?.get("code"))
        assertThrows(IllegalStateException::class.java) {
            service.acknowledge(8, "123456", "123456")
        }
    }
}
