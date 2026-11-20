package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.domain.AuditAction
import bmstu.iu3.moiseev.pedms.dto.DocumentForm
import bmstu.iu3.moiseev.pedms.dto.DocumentValidationException
import bmstu.iu3.moiseev.pedms.entity.AttachmentEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.entity.AuditLogEntity
import bmstu.iu3.moiseev.pedms.repository.AttachmentRepository
import bmstu.iu3.moiseev.pedms.repository.DocTypeRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import bmstu.iu3.moiseev.pedms.repository.AuditLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Service
class DocumentService(
    private val documentRepository: DocumentRepository,
    private val docTypeRepository: DocTypeRepository,
    private val attachmentRepository: AttachmentRepository,
    private val currentUserService: CurrentUserService,
    private val auditLogRepository: AuditLogRepository? = null
) {
    fun findActiveTypes() = docTypeRepository.findAllByIsActiveTrueOrderByNameAsc()

    fun findList(
        typeId: Int?,
        status: DocStatus?,
        text: String?,
        from: LocalDate?,
        to: LocalDate?
    ): List<DocumentEntity> {
        val user = currentUserService.requireCurrentUser()
        val fromDateTime = from?.atStartOfDay()
        val toDateTime = to?.plusDays(1)?.atStartOfDay()?.minusNanos(1)

        val searched = documentRepository.search(typeId, status, null, text, fromDateTime, toDateTime)
        val allowedIds = documentRepository.findAccessibleTo(user.id ?: 0).mapNotNull { it.id }.toSet()
        return searched.filter { it.id in allowedIds }
    }

    fun findById(id: Int): DocumentEntity? {
        val user = currentUserService.requireCurrentUser()
        val document = documentRepository.findById(id).orElse(null) ?: return null
        val isAccessible = document.author?.id == user.id ||
            documentRepository.findAccessibleTo(user.id ?: 0).any { it.id == document.id }
        return document.takeIf { isAccessible }
    }

    @Transactional
    fun create(form: DocumentForm): DocumentEntity {
        val user = currentUserService.requireCurrentUser()
        val (type, content, periodStart, periodEnd) = validateForm(form)
        val title = form.title.trim()

        val saved = documentRepository.save(
            DocumentEntity(
                type = type,
                author = user,
                title = title,
                status = DocStatus.DRAFT,
                contentJson = content,
                periodStart = periodStart,
                periodEnd = periodEnd,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now()
            )
        )
        audit(saved, AuditAction.CREATED, "Создан черновик документа", mapOf("status" to DocStatus.DRAFT.name))
        return saved
    }

    @Transactional
    fun update(id: Int, form: DocumentForm): DocumentEntity {
        val document = findEditableDocument(id)
        val (type, content, periodStart, periodEnd) = validateForm(form)
        document.type = type
        document.title = form.title.trim()
        document.contentJson = content
        document.periodStart = periodStart
        document.periodEnd = periodEnd
        document.updatedAt = LocalDateTime.now()
        val saved = documentRepository.save(document)
        audit(saved, AuditAction.DRAFT_SAVED, "Сохранены изменения черновика", mapOf(
            "title" to saved.title,
            "typeId" to saved.type?.id,
            "contentJson" to saved.contentJson
        ))
        return saved
    }

    @Transactional
    fun recall(id: Int): DocumentEntity {
        val document = findDocumentForAuthor(id)
        if (document.status == DocStatus.IN_APPROVAL) {
            document.status = DocStatus.RECALLED
            document.updatedAt = LocalDateTime.now()
            val saved = documentRepository.save(document)
            audit(saved, AuditAction.RECALLED, "Документ отозван с согласования", mapOf("status" to "IN_APPROVAL -> RECALLED"))
            return saved
        }
        throw IllegalStateException("Отозвать можно только документ, находящийся на согласовании")
    }

    @Transactional
    fun addAttachment(id: Int, file: MultipartFile): AttachmentEntity {
        val document = findEditableDocument(id)
        validateAttachment(file, document)

        val safeName = file.originalFilename?.replace(Regex("[\\\\/:*?\"<>|]"), "_") ?: "file"
        val directory = Path.of("uploads", "documents", id.toString())
        Files.createDirectories(directory)
        val storedName = "${UUID.randomUUID()}_$safeName"
        val target = directory.resolve(storedName)
        Files.copy(file.inputStream, target, StandardCopyOption.REPLACE_EXISTING)

        val attachment = attachmentRepository.save(
            AttachmentEntity(
                document = document,
                uploadedBy = currentUserService.requireCurrentUser(),
                fileName = safeName,
                filePath = target.toString(),
                fileSize = file.size.toInt(),
                fileType = file.contentType
            )
        )
        document.updatedAt = LocalDateTime.now()
        documentRepository.save(document)
        audit(document, AuditAction.DRAFT_SAVED, "Добавлено вложение «${attachment.fileName}»", mapOf(
            "attachmentId" to attachment.id,
            "fileName" to attachment.fileName,
            "fileSize" to attachment.fileSize
        ))
        return attachment
    }

    fun attachments(id: Int): List<AttachmentEntity> =
        attachmentRepository.findAllByDocumentIdOrderByUploadedAtAsc(id)

    private fun findEditableDocument(id: Int): DocumentEntity {
        val document = findDocumentForAuthor(id)
        if (!document.status.isEditable) {
            throw IllegalStateException("Подписанный или отправленный документ нельзя редактировать")
        }
        return document
    }

    private fun findDocumentForAuthor(id: Int): DocumentEntity {
        val user = currentUserService.requireCurrentUser()
        val document = documentRepository.findById(id).orElse(null)
            ?: throw IllegalArgumentException("Документ не найден")
        if (document.author?.id != user.id) {
            throw org.springframework.security.access.AccessDeniedException("Документ доступен только автору")
        }
        return document
    }

    private fun audit(
        document: DocumentEntity,
        action: AuditAction,
        comment: String,
        changedFields: Map<String, Any?>
    ) {
        auditLogRepository?.save(
            AuditLogEntity(
                document = document,
                user = currentUserService.requireCurrentUser(),
                action = action,
                comment = comment,
                changedFields = changedFields,
                createdAt = LocalDateTime.now()
            )
        )
    }

    private fun validateAttachment(file: MultipartFile, document: DocumentEntity) {
        if (file.isEmpty) throw IllegalArgumentException("Файл не выбран")
        if (file.size > 25 * 1024 * 1024) {
            throw IllegalArgumentException("Размер файла не должен превышать 25 МБ")
        }
        val forbidden = setOf(
            "exe", "bat", "cmd", "com", "msi", "scr", "pif", "js", "vbs", "ps1", "sh",
            "jar", "dll"
        )
        val extension = file.originalFilename?.substringAfterLast('.', "")?.lowercase()
        if (extension in forbidden) {
            throw IllegalArgumentException("Исполняемые файлы и скрипты запрещены")
        }
        val documentId = document.id ?: 0
        if (attachmentRepository.sumFileSize(documentId) + file.size > 100 * 1024 * 1024) {
            throw IllegalArgumentException("Суммарный размер вложений не должен превышать 100 МБ")
        }
    }

    private data class ValidatedForm(
        val type: bmstu.iu3.moiseev.pedms.entity.DocTypeEntity,
        val content: Map<String, Any?>,
        val periodStart: LocalDate?,
        val periodEnd: LocalDate?
    )

    private fun validateForm(form: DocumentForm): ValidatedForm {
        val errors = linkedMapOf<String, String>()
        if (form.title.isBlank()) errors["title"] = "Введите название документа"

        if (form.typeId == null) errors["typeId"] = "Выберите вид документа"
        val type = form.typeId?.let { docTypeRepository.findById(it).orElse(null) }
            ?: throw DocumentValidationException(errors)

        if (!type.isActive) errors["typeId"] = "Выбранный вид документа отключён"

        val fields = type.templateJson["fields"] as? List<*> ?: emptyList<Any?>()
        val content = linkedMapOf<String, Any?>()

        fields.forEach { raw ->
            val field = raw as? Map<*, *> ?: return@forEach
            val key = field["key"] as? String ?: return@forEach
            val label = field["label"] as? String ?: key
            val required = field["required"] as? Boolean ?: false
            val fieldType = field["type"] as? String ?: "text"
            val value = form.fields[key]?.trim().orEmpty()
            if (required && value.isBlank()) {
                errors[key] = "Заполните поле «$label»"
            } else if (value.isNotEmpty()) {
                if (fieldType == "date") {
                    try {
                        val date = LocalDate.parse(value)
                        content[key] = date.toString()
                    } catch (_: Exception) {
                        errors[key] = "Введите корректную дату в поле «$label»"
                    }
                } else {
                    content[key] = value
                }
            }
        }

        var periodStart: LocalDate? = form.periodStart
        var periodEnd: LocalDate? = form.periodEnd
        val startValue = content["start_date"] as? String
        val endValue = content["end_date"] as? String
        if (startValue != null) periodStart = LocalDate.parse(startValue)
        if (endValue != null) periodEnd = LocalDate.parse(endValue)
        if (periodStart != null && periodEnd != null && periodStart.isAfter(periodEnd)) {
            errors["periodEnd"] = "Дата окончания не может быть раньше даты начала"
        }

        if (errors.isNotEmpty()) throw DocumentValidationException(errors)
        return ValidatedForm(type, content, periodStart, periodEnd)
    }
}
