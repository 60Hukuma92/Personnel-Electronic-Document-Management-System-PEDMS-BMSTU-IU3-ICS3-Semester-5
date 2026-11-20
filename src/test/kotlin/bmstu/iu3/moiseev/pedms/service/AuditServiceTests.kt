package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.AuditAction
import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.AuditLogEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.repository.AuditLogRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import bmstu.iu3.moiseev.pedms.repository.RouteStepRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.access.AccessDeniedException
import java.util.Optional

class AuditServiceTests {
    private lateinit var auditLogRepository: AuditLogRepository
    private lateinit var documentRepository: DocumentRepository
    private lateinit var routeStepRepository: RouteStepRepository
    private lateinit var currentUserService: CurrentUserService
    private lateinit var auditService: AuditService
    private lateinit var author: AppUserEntity
    private lateinit var document: DocumentEntity

    @BeforeEach
    fun setUp() {
        auditLogRepository = mock(AuditLogRepository::class.java)
        documentRepository = mock(DocumentRepository::class.java)
        routeStepRepository = mock(RouteStepRepository::class.java)
        currentUserService = mock(CurrentUserService::class.java)
        auditService = AuditService(auditLogRepository, documentRepository, routeStepRepository, currentUserService)
        author = AppUserEntity(id = 10, fullName = "Автор", email = "author@pedms.ru", role = UserRole.EMPLOYEE)
        document = DocumentEntity(id = 100, author = author, title = "Документ")
        `when`(documentRepository.findById(100)).thenReturn(Optional.of(document))
        `when`(currentUserService.requireCurrentUser()).thenReturn(author)
    }

    @Test
    fun `history is available to document author`() {
        val entry = AuditLogEntity(document = document, user = author, action = AuditAction.CREATED)
        `when`(auditLogRepository.findAllByDocumentIdOrderByCreatedAtDesc(100)).thenReturn(listOf(entry))
        assertEquals(listOf(entry), auditService.history(100))
    }

    @Test
    fun `history export creates xlsx`() {
        `when`(auditLogRepository.findAllByDocumentIdOrderByCreatedAtDesc(100)).thenReturn(
            listOf(AuditLogEntity(document = document, user = author, action = AuditAction.CREATED))
        )
        val bytes = auditService.exportHistory(100)
        assertTrue(bytes.isNotEmpty())
        assertTrue(bytes.copyOfRange(0, 2).contentEquals(byteArrayOf(0x50, 0x4B)))
    }

    @Test
    fun `audit record rejects later mutation`() {
        val entry = AuditLogEntity(document = document, user = author, action = AuditAction.CREATED)
        assertThrows(IllegalStateException::class.java) { entry.preventMutation() }
    }

    @Test
    fun `history is denied to unrelated employee`() {
        val unrelated = AppUserEntity(id = 20, fullName = "Чужой", email = "other@pedms.ru", role = UserRole.EMPLOYEE)
        `when`(currentUserService.requireCurrentUser()).thenReturn(unrelated)
        `when`(routeStepRepository.findAllByDocumentIdAndUserIdOrderByStepOrderAsc(100, 20)).thenReturn(emptyList())
        assertThrows(AccessDeniedException::class.java) { auditService.history(100) }
    }
}
