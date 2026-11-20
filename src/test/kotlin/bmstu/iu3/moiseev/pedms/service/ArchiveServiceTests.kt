package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DocTypeEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class ArchiveServiceTests {
    private lateinit var documentRepository: DocumentRepository
    private lateinit var auditService: AuditService
    private lateinit var currentUserService: CurrentUserService
    private lateinit var archiveService: ArchiveService

    @BeforeEach
    fun setUp() {
        documentRepository = mock(DocumentRepository::class.java)
        auditService = mock(AuditService::class.java)
        currentUserService = mock(CurrentUserService::class.java)
        archiveService = ArchiveService(documentRepository, auditService, currentUserService)
        `when`(currentUserService.requireCurrentUser()).thenReturn(
            AppUserEntity(id = 1, fullName = "Сотрудник ОК", email = "hr@pedms.ru", role = UserRole.HR)
        )
    }

    @Test
    fun `signed document moves to archive and records audit`() {
        val type = DocTypeEntity(id = 5, code = "ORDER", name = "Приказ", retentionYears = 10)
        val document = DocumentEntity(id = 50, type = type, title = "Исполненный приказ", status = DocStatus.SIGNED)
        `when`(documentRepository.findAllByStatusOrderByCreatedAtDesc(DocStatus.SIGNED))
            .thenReturn(listOf(document))

        assertEquals(1, archiveService.archiveSignedDocuments())
        assertEquals(DocStatus.ARCHIVED, document.status)
        verify(documentRepository).save(document)
        verify(auditService).record(
            document,
            bmstu.iu3.moiseev.pedms.domain.AuditAction.ARCHIVED,
            "Документ переведён в архив",
            mapOf("status" to "SIGNED -> ARCHIVED", "retentionYears" to 10)
        )
    }

    @Test
    fun `retention date uses doc type retention years`() {
        val type = DocTypeEntity(retentionYears = 7)
        val document = DocumentEntity(type = type, updatedAt = java.time.LocalDateTime.of(2026, 1, 15, 10, 0))
        assertEquals(java.time.LocalDateTime.of(2033, 1, 15, 10, 0), archiveService.retentionUntil(document))
    }
}
