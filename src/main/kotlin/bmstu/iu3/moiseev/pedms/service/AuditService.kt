package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.AuditAction
import bmstu.iu3.moiseev.pedms.entity.AuditLogEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.repository.AuditLogRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import bmstu.iu3.moiseev.pedms.repository.RouteStepRepository
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class AuditService(
    private val auditLogRepository: AuditLogRepository,
    private val documentRepository: DocumentRepository,
    private val routeStepRepository: RouteStepRepository,
    private val currentUserService: CurrentUserService
) {
    @Transactional
    fun record(
        document: DocumentEntity?,
        action: AuditAction,
        comment: String? = null,
        changedFields: Map<String, Any?> = emptyMap()
    ): AuditLogEntity =
        auditLogRepository.save(
            AuditLogEntity(
                document = document,
                user = currentUserService.requireCurrentUser(),
                action = action,
                comment = comment,
                changedFields = changedFields.takeIf { it.isNotEmpty() },
                createdAt = LocalDateTime.now()
            )
        )

    fun history(documentId: Int): List<AuditLogEntity> {
        val user = currentUserService.requireCurrentUser()
        val document = documentRepository.findById(documentId).orElse(null)
            ?: throw IllegalArgumentException("Документ не найден")

        val isParticipant = document.author?.id == user.id ||
            routeStepRepository.findAllByDocumentIdAndUserIdOrderByStepOrderAsc(documentId, user.id ?: 0).isNotEmpty()
        val isArchiveOrAdmin = user.role.isAtLeastHr
        if (!isParticipant && !isArchiveOrAdmin) {
            throw AccessDeniedException("Просмотр истории документа запрещён")
        }
        return auditLogRepository.findAllByDocumentIdOrderByCreatedAtDesc(documentId)
    }

    fun exportHistory(documentId: Int): ByteArray {
        val rows = history(documentId)
        val document = documentRepository.findById(documentId).orElse(null)
            ?: throw IllegalArgumentException("Документ не найден")
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("История")
            val headers = listOf("Дата и время", "Пользователь", "Действие", "Комментарий", "Изменённые поля")
            val header = sheet.createRow(0)
            headers.forEachIndexed { index, title -> header.createCell(index).setCellValue(title) }

            rows.forEachIndexed { rowIndex, entry ->
                val row = sheet.createRow(rowIndex + 1)
                row.createCell(0).setCellValue(entry.createdAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")))
                row.createCell(1).setCellValue(entry.user?.fullName ?: "")
                row.createCell(2).setCellValue(entry.action.title)
                row.createCell(3).setCellValue(entry.comment ?: "")
                row.createCell(4).setCellValue(formatChangedFields(entry.changedFields))
            }
            headers.indices.forEach { sheet.autoSizeColumn(it) }
            sheet.createFreezePane(0, 1)
            val footer = sheet.createRow(rows.size + 2)
            footer.createCell(0).setCellValue("Документ")
            footer.createCell(1).setCellValue(document.number ?: document.title)
            return workbookToBytes(workbook)
        }
    }

    fun preventMutation(entity: AuditLogEntity) {
        throw IllegalStateException("Записи журнала аудита нельзя изменять или удалять")
    }

    private fun formatChangedFields(fields: Map<String, Any?>?): String =
        fields?.entries?.joinToString("; ") { "${it.key}=${it.value}" } ?: ""

    private fun workbookToBytes(workbook: Workbook): ByteArray =
        ByteArrayOutputStream().use { output ->
            workbook.write(output)
            output.toByteArray()
        }
}
