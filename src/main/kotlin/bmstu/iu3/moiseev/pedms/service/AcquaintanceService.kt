package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.AudienceType
import bmstu.iu3.moiseev.pedms.domain.AuditAction
import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.domain.MarkStatus
import bmstu.iu3.moiseev.pedms.dto.AcquaintancePreview
import bmstu.iu3.moiseev.pedms.dto.AcquaintanceSheetForm
import bmstu.iu3.moiseev.pedms.entity.AcquaintanceMarkEntity
import bmstu.iu3.moiseev.pedms.entity.AcquaintanceSheetEntity
import bmstu.iu3.moiseev.pedms.entity.AuditLogEntity
import bmstu.iu3.moiseev.pedms.repository.AcquaintanceMarkRepository
import bmstu.iu3.moiseev.pedms.repository.AcquaintanceSheetRepository
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.AuditLogRepository
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import bmstu.iu3.moiseev.pedms.repository.PositionRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Service
class AcquaintanceService(
    private val sheetRepository: AcquaintanceSheetRepository,
    private val markRepository: AcquaintanceMarkRepository,
    private val documentRepository: DocumentRepository,
    private val userRepository: AppUserRepository,
    private val departmentRepository: DepartmentRepository,
    private val positionRepository: PositionRepository,
    private val auditLogRepository: AuditLogRepository,
    private val currentUserService: CurrentUserService,
    private val signatureService: SignatureService
) {
    fun documentsForCreation() = documentRepository.findAllByOrderByCreatedAtDesc()
        .filter { it.status == DocStatus.SIGNED || it.isSigned == true }

    fun departments() = departmentRepository.findAll().filter { it.isActive }.sortedBy { it.name }

    fun positions() = positionRepository.findAllByOrderByNameAsc()

    fun preview(form: AcquaintanceSheetForm): AcquaintancePreview {
        validateReference(form)
        val users = resolveAudience(form.audienceType, form.reference())
        return AcquaintancePreview(users, users.size)
    }

    @Transactional
    fun create(form: AcquaintanceSheetForm): AcquaintanceSheetEntity {
        if (!currentUserService.hasRole(bmstu.iu3.moiseev.pedms.domain.UserRole.HR)) {
            throw AccessDeniedException("Создавать листы ознакомления могут только сотрудники ОК")
        }
        val documentId = form.documentId ?: throw IllegalArgumentException("Выберите документ")
        val document = documentRepository.findById(documentId).orElseThrow {
            IllegalArgumentException("Документ не найден")
        }
        if (document.status != DocStatus.SIGNED && document.isSigned != true) {
            throw IllegalArgumentException("Лист можно создать только для подписанного документа")
        }
        if (form.deadline == null) throw IllegalArgumentException("Укажите срок ознакомления")
        if (form.deadline!!.isBefore(LocalDate.now())) {
            throw IllegalArgumentException("Срок ознакомления не может быть в прошлом")
        }
        val users = preview(form).users
        if (users.isEmpty()) throw IllegalArgumentException("Аудитория не содержит активных сотрудников")
        val creator = currentUserService.requireCurrentUser()
        val sheet = sheetRepository.save(
            AcquaintanceSheetEntity(
                document = document,
                createdBy = creator,
                audienceType = form.audienceType,
                audienceRef = form.reference(),
                deadline = form.deadline,
                totalExpected = users.size,
                acknowledgedCount = 0
            )
        )
        markRepository.saveAll(users.map { AcquaintanceMarkEntity(sheet = sheet, user = it) })
        auditLogRepository.save(
            AuditLogEntity(
                document = document,
                user = creator,
                action = AuditAction.SHEET_CREATED,
                comment = "Создан лист ознакомления для аудитории «${form.audienceType.title}»",
                changedFields = mapOf("sheetId" to sheet.id, "totalExpected" to users.size)
            )
        )
        return sheet
    }

    fun sheets(): List<AcquaintanceSheetEntity> {
        val user = currentUserService.requireCurrentUser()
        return if (currentUserService.hasRole(bmstu.iu3.moiseev.pedms.domain.UserRole.HR)) {
            sheetRepository.findAllByOrderByCreatedAtDesc()
        } else {
            markRepository.findAllByUserIdAndStatus(user.id ?: 0, MarkStatus.PENDING)
                .mapNotNull { it.sheet }
                .distinctBy { it.id }
        }
    }

    fun findSheet(id: Int): AcquaintanceSheetEntity =
        sheetRepository.findById(id).orElseThrow { IllegalArgumentException("Лист ознакомления не найден") }

    fun marks(sheetId: Int): List<AcquaintanceMarkEntity> =
        markRepository.findAllBySheetIdOrderByIdAsc(sheetId)

    fun markForCurrentUser(sheetId: Int): AcquaintanceMarkEntity? =
        markRepository.findBySheetIdAndUserId(sheetId, currentUserService.requireCurrentUser().id ?: 0)

    fun summary(sheetId: Int): AcquaintanceSummary {
        val marks = marks(sheetId)
        val acknowledged = marks.count { it.status == MarkStatus.ACKNOWLEDGED }
        return AcquaintanceSummary(
            total = marks.size,
            acknowledged = acknowledged,
            pending = marks.size - acknowledged,
            percent = if (marks.isEmpty()) 0 else acknowledged * 100 / marks.size
        )
    }

    @Transactional
    fun acknowledge(sheetId: Int, expectedCode: String, code: String): AcquaintanceMarkEntity {
        val user = currentUserService.requireCurrentUser()
        val mark = markRepository.findBySheetIdAndUserId(sheetId, user.id ?: 0)
            ?: throw AccessDeniedException("Вы не включены в этот лист ознакомления")
        if (mark.status == MarkStatus.ACKNOWLEDGED) {
            throw IllegalStateException("Ознакомление уже подтверждено")
        }
        if (!signatureService.verifyCode(expectedCode, code)) {
            throw IllegalArgumentException("Неверный код подтверждения")
        }
        val now = LocalDateTime.now()
        mark.status = MarkStatus.ACKNOWLEDGED
        mark.readAt = now
        mark.signatureData = mapOf(
            "type" to "SIMPLE_EP",
            "code" to code.trim(),
            "signedAt" to now.toString(),
            "requestId" to UUID.randomUUID().toString()
        )
        return markRepository.save(mark)
    }

    fun pendingCsv(sheetId: Int): ByteArray {
        val lines = mutableListOf("ФИО;Email;Подразделение;Должность")
        marks(sheetId).filter { it.status == MarkStatus.PENDING }.forEach { mark ->
            val user = mark.user ?: return@forEach
            lines += listOf(
                user.fullName,
                user.email,
                user.department?.name.orEmpty(),
                user.position?.name.orEmpty()
            ).joinToString(";") { it.replace(";", ",") }
        }
        return ("\uFEFF" + lines.joinToString("\r\n") + "\r\n").toByteArray(Charsets.UTF_8)
    }

    private fun validateReference(form: AcquaintanceSheetForm) {
        if (form.audienceType == AudienceType.ALL) return
        if (form.reference().isNullOrBlank()) throw IllegalArgumentException("Укажите параметры аудитории")
    }

    private fun resolveAudience(type: AudienceType, reference: String?): List<bmstu.iu3.moiseev.pedms.entity.AppUserEntity> =
        when (type) {
            AudienceType.ALL -> userRepository.findAllByStatusOrderByFullNameAsc(bmstu.iu3.moiseev.pedms.domain.UserStatus.ACTIVE)
            AudienceType.DEPARTMENT -> {
                val id = reference?.toIntOrNull() ?: throw IllegalArgumentException("Некорректное подразделение")
                if (!departmentRepository.existsById(id)) throw IllegalArgumentException("Подразделение не найдено")
                userRepository.findAllByDepartmentIdAndStatusOrderByFullNameAsc(
                    id, bmstu.iu3.moiseev.pedms.domain.UserStatus.ACTIVE
                )
            }
            AudienceType.POSITION -> {
                val id = reference?.toIntOrNull() ?: throw IllegalArgumentException("Некорректная должность")
                if (!positionRepository.existsById(id)) throw IllegalArgumentException("Должность не найдена")
                userRepository.findAllByPositionIdAndStatusOrderByFullNameAsc(
                    id, bmstu.iu3.moiseev.pedms.domain.UserStatus.ACTIVE
                )
            }
            AudienceType.LIST -> {
                val ids = reference.orEmpty().split(',', ';', ' ', '\n')
                    .mapNotNull { it.trim().toIntOrNull() }.distinct()
                if (ids.isEmpty()) throw IllegalArgumentException("Укажите ID сотрудников через запятую")
                userRepository.findAllById(ids)
                    .filter { it.status == bmstu.iu3.moiseev.pedms.domain.UserStatus.ACTIVE }
                    .sortedBy { it.fullName }
            }
        }
}

data class AcquaintanceSummary(
    val total: Int,
    val acknowledged: Int,
    val pending: Int,
    val percent: Int
)
