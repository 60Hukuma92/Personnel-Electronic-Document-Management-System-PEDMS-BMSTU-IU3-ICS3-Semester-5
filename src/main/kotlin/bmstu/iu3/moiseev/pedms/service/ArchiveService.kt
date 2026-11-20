package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.AuditAction
import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ArchiveService(
    private val documentRepository: DocumentRepository,
    private val auditService: AuditService,
    private val currentUserService: CurrentUserService
) {
    fun requireArchiveAccess() {
        val user = currentUserService.requireCurrentUser()
        if (!user.role.isAtLeastHr) throw AccessDeniedException("Доступ к архиву разрешён сотрудникам ОК и администраторам")
    }

    fun findArchive(text: String?, typeId: Int?): List<DocumentEntity> {
        requireArchiveAccess()
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() }
        val archived = documentRepository.findAllByStatusOrderByCreatedAtDesc(DocStatus.ARCHIVED)
        return archived.filter { document ->
            (typeId == null || document.type?.id == typeId) &&
                (normalized == null ||
                    document.title.contains(normalized, ignoreCase = true) ||
                    (document.number?.contains(normalized, ignoreCase = true) ?: false))
        }
    }

    @Transactional
    fun archiveSignedDocuments(): Int {
        requireArchiveAccess()
        val documents = documentRepository.findAllByStatusOrderByCreatedAtDesc(DocStatus.SIGNED)
        var count = 0
        documents.forEach { document ->
            document.status = DocStatus.ARCHIVED
            document.updatedAt = LocalDateTime.now()
            documentRepository.save(document)
            auditService.record(
                document,
                AuditAction.ARCHIVED,
                "Документ переведён в архив",
                mapOf(
                    "status" to "SIGNED -> ARCHIVED",
                    "retentionYears" to (document.type?.retentionYears ?: 5)
                )
            )
            count++
        }
        return count
    }

    fun retentionUntil(document: DocumentEntity): LocalDateTime =
        document.updatedAt.plusYears((document.type?.retentionYears ?: 5).toLong())
}
