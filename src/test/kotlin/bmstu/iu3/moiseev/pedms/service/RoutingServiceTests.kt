package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.DocCategory
import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.domain.StepAction
import bmstu.iu3.moiseev.pedms.domain.StepStatus
import bmstu.iu3.moiseev.pedms.domain.UserStatus
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DepartmentEntity
import bmstu.iu3.moiseev.pedms.entity.DocTypeEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.entity.PositionEntity
import bmstu.iu3.moiseev.pedms.entity.RouteRuleEntity
import bmstu.iu3.moiseev.pedms.entity.RouteStepEntity
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.AuditLogRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import bmstu.iu3.moiseev.pedms.repository.RouteRuleRepository
import bmstu.iu3.moiseev.pedms.repository.RouteStepRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional

class RoutingServiceTests {
    private lateinit var documentRepository: DocumentRepository
    private lateinit var routeRuleRepository: RouteRuleRepository
    private lateinit var routeStepRepository: RouteStepRepository
    private lateinit var appUserRepository: AppUserRepository
    private lateinit var auditLogRepository: AuditLogRepository
    private lateinit var currentUserService: CurrentUserService
    private lateinit var routingService: RoutingService
    private lateinit var author: AppUserEntity
    private lateinit var approver: AppUserEntity
    private lateinit var document: DocumentEntity

    @BeforeEach
    fun setUp() {
        documentRepository = mock(DocumentRepository::class.java)
        routeRuleRepository = mock(RouteRuleRepository::class.java)
        routeStepRepository = mock(RouteStepRepository::class.java)
        appUserRepository = mock(AppUserRepository::class.java)
        auditLogRepository = mock(AuditLogRepository::class.java)
        currentUserService = mock(CurrentUserService::class.java)
        routingService = RoutingService(
            documentRepository,
            routeRuleRepository,
            routeStepRepository,
            appUserRepository,
            auditLogRepository,
            currentUserService
        )

        val department = DepartmentEntity(id = 5, name = "Отдел разработки", code = "DEV", path = "/1/5/")
        val authorPosition = PositionEntity(id = 1, name = "Инженер")
        val approverPosition = PositionEntity(id = 2, name = "Руководитель отдела")
        author = AppUserEntity(
            id = 10,
            department = department,
            position = authorPosition,
            fullName = "Автор",
            email = "author@pedms.ru",
            status = UserStatus.ACTIVE
        )
        approver = AppUserEntity(
            id = 20,
            department = department,
            position = approverPosition,
            fullName = "Согласующий",
            email = "approver@pedms.ru",
            status = UserStatus.ACTIVE
        )
        document = DocumentEntity(
            id = 100,
            type = DocTypeEntity(
                id = 3,
                code = "TEST",
                name = "Тестовый документ",
                category = DocCategory.MEMO
            ),
            author = author,
            title = "Тест",
            status = DocStatus.DRAFT
        )

        `when`(currentUserService.requireCurrentUser()).thenReturn(author)
        `when`(currentUserService.currentUser()).thenReturn(author)
        `when`(documentRepository.findById(100)).thenReturn(Optional.of(document))
    }

    @Test
    fun `submit requires manual route when rule is missing`() {
        `when`(routeStepRepository.findAllByDocumentIdOrderByStepOrderAsc(100)).thenReturn(emptyList())
        `when`(routeRuleRepository.findMatching(3, 5, 1)).thenReturn(emptyList())

        val exception = assertThrows(IllegalStateException::class.java) {
            routingService.submit(100)
        }

        assertEquals(
            "Для документа не найдено правило маршрутизации. Соберите маршрут вручную",
            exception.message
        )
    }

    @Test
    fun `submit builds and activates route from rule`() {
        val rule = RouteRuleEntity(
            type = document.type,
            department = null,
            initiatorPosition = null,
            approverPosition = PositionEntity(id = 2, name = "Руководитель отдела"),
            approverDepartment = null,
            stepOrder = 1,
            action = StepAction.APPROVE,
            deadlineDays = 2
        )
        `when`(routeStepRepository.findAllByDocumentIdOrderByStepOrderAsc(100)).thenReturn(emptyList())
        `when`(routeRuleRepository.findMatching(3, 5, 1)).thenReturn(listOf(rule))
        `when`(appUserRepository.findActiveByDepartmentAndPosition(5, 2)).thenReturn(listOf(approver))
        `when`(routeStepRepository.save(any(RouteStepEntity::class.java))).thenAnswer { it.arguments[0] }
        `when`(documentRepository.save(document)).thenReturn(document)

        val result = routingService.submit(100)

        assertEquals(DocStatus.IN_APPROVAL, result.status)
        verify(routeStepRepository).deleteAllByDocumentId(100)
        verify(routeStepRepository).save(any(RouteStepEntity::class.java))
    }

    @Test
    fun `approval advances current step and keeps transaction fields`() {
        document.status = DocStatus.IN_APPROVAL
        val first = RouteStepEntity(
            id = 1,
            document = document,
            user = approver,
            stepOrder = 1,
            action = StepAction.APPROVE,
            status = StepStatus.IN_PROGRESS
        )
        val second = RouteStepEntity(
            id = 2,
            document = document,
            user = approver,
            stepOrder = 2,
            action = StepAction.APPROVE,
            status = StepStatus.PENDING
        )
        `when`(routeStepRepository.findAllByDocumentIdOrderByStepOrderAsc(100))
            .thenReturn(listOf(first, second))
        `when`(currentUserService.requireCurrentUser()).thenReturn(approver)
        `when`(routeStepRepository.save(any(RouteStepEntity::class.java))).thenAnswer { it.arguments[0] }

        val result = routingService.approve(100, "Согласовано")

        assertEquals(DocStatus.IN_APPROVAL, result.status)
        assertEquals(StepStatus.APPROVED, first.status)
        assertEquals(StepStatus.IN_PROGRESS, second.status)
    }

    @Test
    fun `delegate moves active step to target and keeps source history`() {
        document.status = DocStatus.IN_APPROVAL
        val target = AppUserEntity(
            id = 30,
            fullName = "Заместитель",
            email = "target@pedms.ru",
            status = UserStatus.ACTIVE
        )
        val current = RouteStepEntity(
            id = 1,
            document = document,
            user = approver,
            stepOrder = 1,
            action = StepAction.APPROVE,
            status = StepStatus.IN_PROGRESS
        )
        `when`(currentUserService.requireCurrentUser()).thenReturn(approver)
        `when`(routeStepRepository.findAllByDocumentIdOrderByStepOrderAsc(100))
            .thenReturn(listOf(current))
        `when`(appUserRepository.findById(30)).thenReturn(Optional.of(target))
        `when`(routeStepRepository.save(any(RouteStepEntity::class.java))).thenAnswer { it.arguments[0] }

        routingService.delegate(100, 30, "В отпуске")

        assertEquals(target, current.user)
        assertEquals(approver, current.delegatedFrom)
        assertEquals(StepStatus.PENDING, current.status)
    }
}
