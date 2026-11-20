package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.AuditAction
import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.domain.StepAction
import bmstu.iu3.moiseev.pedms.domain.StepStatus
import bmstu.iu3.moiseev.pedms.domain.UserStatus
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.AuditLogEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.entity.RouteRuleEntity
import bmstu.iu3.moiseev.pedms.entity.RouteStepEntity
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.AuditLogRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import bmstu.iu3.moiseev.pedms.repository.RouteRuleRepository
import bmstu.iu3.moiseev.pedms.repository.RouteStepRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class RouteDraft(
    val userId: Int,
    val action: StepAction,
    val deadlineDays: Int,
    val stepOrder: Int
)

@Service
class RoutingService(
    private val documentRepository: DocumentRepository,
    private val routeRuleRepository: RouteRuleRepository,
    private val routeStepRepository: RouteStepRepository,
    private val appUserRepository: AppUserRepository,
    private val auditLogRepository: AuditLogRepository,
    private val currentUserService: CurrentUserService,
    private val notificationService: NotificationService
) {

    fun findRoute(documentId: Int): List<RouteStepEntity> =
        routeStepRepository.findAllByDocumentIdOrderByStepOrderAsc(documentId)

    fun findHistory(documentId: Int): List<AuditLogEntity> =
        auditLogRepository.findAllByDocumentIdOrderByCreatedAtDesc(documentId)

    fun availableApprovers(): List<AppUserEntity> =
        appUserRepository.findAllByStatusOrderByFullNameAsc(UserStatus.ACTIVE)

    fun canAct(documentId: Int): Boolean {
        val user = currentUserService.currentUser() ?: return false
        val current = routeStepRepository.findAllByDocumentIdOrderByStepOrderAsc(documentId)
            .firstOrNull { it.status == StepStatus.IN_PROGRESS }
        return current?.user?.id == user.id
    }

    fun previewRoute(documentId: Int): List<RouteStepEntity> {
        val document = findDocument(documentId)
        val persisted = findRoute(documentId)
        if (persisted.isNotEmpty()) return persisted
        return buildRoute(document)
    }

    @Transactional
    fun saveRoute(documentId: Int, drafts: List<RouteDraft>): DocumentEntity {
        val document = findDocumentForAuthor(documentId)
        if (!document.status.isEditable) {
            throw IllegalStateException("Маршрут можно менять только до отправки или при доработке")
        }
        if (drafts.isEmpty()) throw IllegalArgumentException("Маршрут должен содержать хотя бы один этап")
        if (drafts.any { it.deadlineDays <= 0 }) {
            throw IllegalArgumentException("Срок каждого этапа должен быть больше нуля")
        }

        routeStepRepository.deleteAllByDocumentId(documentId)
        val now = LocalDateTime.now()
        drafts.sortedBy { it.stepOrder }.forEachIndexed { index, draft ->
            val user = appUserRepository.findById(draft.userId).orElse(null)
                ?: throw IllegalArgumentException("Согласующий не найден")
            if (user.status != UserStatus.ACTIVE) {
                throw IllegalArgumentException("Согласующий не является активным пользователем")
            }
            routeStepRepository.save(
                RouteStepEntity(
                    document = document,
                    user = user,
                    stepOrder = index + 1,
                    action = draft.action,
                    status = StepStatus.PENDING,
                    deadline = addWorkingDays(now, draft.deadlineDays)
                )
            )
        }
        audit(document, AuditAction.ROUTE_CHANGED, "Маршрут скорректирован")
        document.updatedAt = LocalDateTime.now()
        return documentRepository.save(document)
    }

    @Transactional
    fun submit(documentId: Int): DocumentEntity {
        val document = findDocumentForAuthor(documentId)
        if (!document.status.isEditable) {
            throw IllegalStateException("Отправить можно только черновик или документ на доработке")
        }

        val route = findRoute(documentId)
        val finalRoute = if (route.isEmpty()) buildRoute(document) else route
        if (finalRoute.isEmpty()) {
            throw IllegalStateException("Для документа не найдено правило маршрутизации. Соберите маршрут вручную")
        }

        routeStepRepository.deleteAllByDocumentId(documentId)
        val now = LocalDateTime.now()
        finalRoute.sortedBy { it.stepOrder }.forEachIndexed { index, step ->
            val deadline = if (route.isNotEmpty()) {
                step.deadline ?: addWorkingDays(now, 3)
            } else {
                step.deadline ?: addWorkingDays(now, 3)
            }
            routeStepRepository.save(
                RouteStepEntity(
                    document = document,
                    user = step.user,
                    delegatedFrom = step.delegatedFrom,
                    stepOrder = index + 1,
                    action = step.action,
                    status = if (index == 0) StepStatus.IN_PROGRESS else StepStatus.PENDING,
                    deadline = deadline
                )
            )
        }

        document.status = DocStatus.IN_APPROVAL
        document.updatedAt = now
        documentRepository.save(document)
        finalRoute.firstOrNull()?.user?.let {
            notificationService.notify(
                it,
                bmstu.iu3.moiseev.pedms.domain.NotificationEvent.DOCUMENT_ARRIVED,
                document,
                "Документ «${document.title}» поступил на согласование"
            )
        }
        audit(document, AuditAction.SUBMITTED, "Документ отправлен на согласование")
        return document
    }

    @Transactional
    fun approve(documentId: Int, comment: String?): DocumentEntity =
        decide(documentId, true, comment)

    @Transactional
    fun reject(documentId: Int, comment: String): DocumentEntity {
        if (comment.isBlank()) throw IllegalArgumentException("Укажите комментарий при отклонении")
        return decide(documentId, false, comment)
    }

    @Transactional
    fun returnForRework(documentId: Int, comment: String): DocumentEntity {
        if (comment.isBlank()) throw IllegalArgumentException("Укажите причину возврата")
        val document = findDocument(documentId)
        if (document.status != DocStatus.IN_APPROVAL) {
            throw IllegalStateException("Возврат возможен только для документа на согласовании")
        }
        val current = requireCurrentStep(document)
        val now = LocalDateTime.now()
        current.status = StepStatus.RETURNED
        current.comment = comment.trim()
        current.decidedAt = now
        routeStepRepository.save(current)
        document.status = DocStatus.REWORK
        document.updatedAt = now
        documentRepository.save(document)
        document.author?.let {
            notificationService.notify(
                it,
                bmstu.iu3.moiseev.pedms.domain.NotificationEvent.DOCUMENT_RETURNED,
                document,
                "Документ «${document.title}» возвращён на доработку"
            )
        }
        notifyComment(document, comment)
        audit(document, AuditAction.RETURNED, comment.trim())
        return document
    }

    @Transactional
    fun delegate(documentId: Int, targetUserId: Int, comment: String?): DocumentEntity {
        val document = findDocument(documentId)
        if (document.status != DocStatus.IN_APPROVAL) {
            throw IllegalStateException("Делегировать можно только этап документа на согласовании")
        }
        val current = requireCurrentStep(document)
        val target = appUserRepository.findById(targetUserId).orElse(null)
            ?: throw IllegalArgumentException("Пользователь для делегирования не найден")
        if (target.status != UserStatus.ACTIVE) {
            throw IllegalArgumentException("Нельзя делегировать неактивному пользователю")
        }
        if (target.id == current.user?.id) {
            throw IllegalArgumentException("Этап уже назначен этому пользователю")
        }

        val original = current.delegatedFrom ?: current.user
        current.delegatedFrom = original
        current.user = target
        current.status = StepStatus.PENDING
        current.comment = comment?.trim()?.takeIf { it.isNotEmpty() }
        routeStepRepository.save(current)
        document.updatedAt = LocalDateTime.now()
        documentRepository.save(document)
        audit(
            document,
            AuditAction.DELEGATED,
            "Этап делегирован от ${original?.fullName ?: "участника"} пользователю ${target.fullName}"
        )
        return document
    }

    private fun decide(documentId: Int, approved: Boolean, comment: String?): DocumentEntity {
        val document = findDocument(documentId)
        if (document.status != DocStatus.IN_APPROVAL) {
            throw IllegalStateException("Решение доступно только для документа на согласовании")
        }
        val current = requireCurrentStep(document)
        val now = LocalDateTime.now()
        current.status = if (approved) StepStatus.APPROVED else StepStatus.REJECTED
        current.comment = comment?.trim()?.takeIf { it.isNotEmpty() }
        current.decidedAt = now
        routeStepRepository.save(current)

        if (!approved) {
            routeStepRepository.findAllByDocumentIdOrderByStepOrderAsc(documentId)
                .filter { it.status.isPending && it.id != current.id }
                .forEach {
                    it.status = StepStatus.SKIPPED
                    routeStepRepository.save(it)
                }
            document.status = DocStatus.REJECTED
            document.updatedAt = now
            documentRepository.save(document)
            document.author?.let {
                notificationService.notify(
                    it,
                    bmstu.iu3.moiseev.pedms.domain.NotificationEvent.DOCUMENT_REJECTED,
                    document,
                    "Документ «${document.title}» отклонён"
                )
            }
            notifyComment(document, comment)
            audit(document, AuditAction.REJECTED, comment?.trim())
            return document
        }

        val next = routeStepRepository.findAllByDocumentIdOrderByStepOrderAsc(documentId)
            .firstOrNull { it.status == StepStatus.PENDING }
        if (next != null) {
            next.status = StepStatus.IN_PROGRESS
            routeStepRepository.save(next)
        } else {
            document.status = if (current.action == StepAction.SIGN) {
                current.signatureData = mapOf(
                    "signedAt" to now.toString(),
                    "signedByUserId" to (current.user?.id ?: 0),
                    "signedBy" to (current.user?.fullName ?: "")
                )
                routeStepRepository.save(current)
                DocStatus.SIGNED
            } else {
                DocStatus.APPROVED
            }
            document.updatedAt = now
            documentRepository.save(document)
            document.author?.let {
                notificationService.notify(
                    it,
                    if (document.status == DocStatus.SIGNED) {
                        bmstu.iu3.moiseev.pedms.domain.NotificationEvent.DOCUMENT_SIGNED
                    } else {
                        bmstu.iu3.moiseev.pedms.domain.NotificationEvent.DOCUMENT_APPROVED
                    },
                    document,
                    if (document.status == DocStatus.SIGNED) {
                        "Документ «${document.title}» подписан"
                    } else {
                        "Документ «${document.title}» согласован"
                    }
                )
            }
            next?.user?.let {
                notificationService.notify(
                    it,
                    bmstu.iu3.moiseev.pedms.domain.NotificationEvent.DOCUMENT_ARRIVED,
                    document,
                    "Документ «${document.title}» поступил на согласование"
                )
            }
        }
        notifyComment(document, comment)
        audit(
            document,
            if (document.status == DocStatus.SIGNED) AuditAction.SIGNED else AuditAction.APPROVED,
            comment?.trim()
        )
        return document
    }

    private fun notifyComment(document: DocumentEntity, comment: String?) {
        if (!comment.isNullOrBlank()) {
            document.author?.let {
                notificationService.notify(
                    it,
                    bmstu.iu3.moiseev.pedms.domain.NotificationEvent.COMMENT_ADDED,
                    document,
                    "К документу «${document.title}» добавлен комментарий"
                )
            }
        }
    }

    private fun requireCurrentStep(document: DocumentEntity): RouteStepEntity {
        val user = currentUserService.requireCurrentUser()
        val documentId = document.id ?: throw IllegalArgumentException("Документ не сохранён")
        val current = routeStepRepository.findAllByDocumentIdOrderByStepOrderAsc(documentId)
            .firstOrNull { it.status == StepStatus.IN_PROGRESS }
            ?: throw IllegalStateException("Активный этап согласования не найден")
        if (current.user?.id != user.id) {
            throw AccessDeniedException("Текущий этап назначен другому пользователю")
        }
        return current
    }

    private fun findDocument(documentId: Int): DocumentEntity =
        documentRepository.findById(documentId).orElse(null)
            ?: throw IllegalArgumentException("Документ не найден")

    private fun findDocumentForAuthor(documentId: Int): DocumentEntity {
        val document = findDocument(documentId)
        val user = currentUserService.requireCurrentUser()
        if (document.author?.id != user.id) {
            throw AccessDeniedException("Маршрут доступен только автору документа")
        }
        return document
    }

    private fun buildRoute(document: DocumentEntity): List<RouteStepEntity> {
        val typeId = document.type?.id ?: return emptyList()
        val authorDepartmentId = document.author?.department?.id
        val authorPositionId = document.author?.position?.id
        val rules = routeRuleRepository.findMatching(typeId, authorDepartmentId, authorPositionId)
        val now = LocalDateTime.now()
        return rules.mapNotNull { rule ->
            val approvers = resolveApprovers(document, rule)
            approvers.firstOrNull()?.let { approver ->
                RouteStepEntity(
                    document = document,
                    user = approver,
                    stepOrder = rule.stepOrder,
                    action = rule.action,
                    status = StepStatus.PENDING,
                    deadline = addWorkingDays(now, rule.deadlineDays)
                )
            }
        }.sortedBy { it.stepOrder }
    }

    private fun resolveApprovers(document: DocumentEntity, rule: RouteRuleEntity): List<AppUserEntity> {
        val positionId = rule.approverPosition?.id
        if (positionId == null) return emptyList()
        val department = rule.approverDepartment
            ?: document.author?.department
            ?: return emptyList()

        department.manager?.takeIf { it.position?.id == positionId && it.status == UserStatus.ACTIVE }
            ?.let { return listOf(it) }

        appUserRepository.findActiveByDepartmentAndPosition(department.id ?: 0, positionId)
            .takeIf { it.isNotEmpty() }?.let { return it }

        appUserRepository.findActiveByPathAndPosition("${department.path}%", positionId)
            .takeIf { it.isNotEmpty() }?.let { return it }

        var parent = department.parent
        while (parent != null) {
            parent.manager?.takeIf { it.position?.id == positionId && it.status == UserStatus.ACTIVE }
                ?.let { return listOf(it) }
            parent = parent.parent
        }
        return emptyList()
    }

    private fun addWorkingDays(start: LocalDateTime, days: Int): LocalDateTime {
        var result = start
        var remaining = days
        while (remaining > 0) {
            result = result.plusDays(1)
            if (result.dayOfWeek.value <= 5) remaining--
        }
        return result
    }

    private fun audit(document: DocumentEntity, action: AuditAction, comment: String?) {
        auditLogRepository.save(
            AuditLogEntity(
                document = document,
                user = currentUserService.requireCurrentUser(),
                action = action,
                comment = comment,
                createdAt = LocalDateTime.now()
            )
        )
    }
}
