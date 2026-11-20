package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.repository.AcquaintanceMarkRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import bmstu.iu3.moiseev.pedms.repository.NotificationRepository
import bmstu.iu3.moiseev.pedms.repository.RouteStepRepository
import org.springframework.stereotype.Service

/**
 * Данные личного кабинета (макет рисунка 2.4 записки).
 *
 * Виджеты кабинета: «Мои документы», «Требуют ознакомления»,
 * «Быстрые действия» и список последних уведомлений.
 */
@Service
class DashboardService(
    private val documentRepository: DocumentRepository,
    private val routeStepRepository: RouteStepRepository,
    private val acquaintanceMarkRepository: AcquaintanceMarkRepository,
    private val notificationRepository: NotificationRepository
) {

    /** Сводка показателей личного кабинета. */
    data class Summary(
        val myDocuments: Long,
        val onApproval: Long,
        val awaitingMe: Long,
        val acquaintanceRequired: Long,
        val unreadNotifications: Long
    )

    /** Сводка показателей по текущему пользователю. */
    fun summary(user: AppUserEntity): Summary {
        val userId = user.id ?: 0
        return Summary(
            myDocuments = documentRepository.findAllByAuthorIdOrderByCreatedAtDesc(userId).size.toLong(),
            onApproval = documentRepository.findAllByStatusOrderByCreatedAtDesc(
                bmstu.iu3.moiseev.pedms.domain.DocStatus.IN_APPROVAL
            ).size.toLong(),
            awaitingMe = routeStepRepository.findPendingFor(userId).size.toLong(),
            acquaintanceRequired = acquaintanceMarkRepository
                .findAllByUserIdAndStatus(userId, bmstu.iu3.moiseev.pedms.domain.MarkStatus.PENDING)
                .size.toLong(),
            unreadNotifications = notificationRepository.countUnread(userId)
        )
    }

    /** Последние уведомления пользователя. */
    fun lastNotifications(user: AppUserEntity, limit: Int = 5) =
        notificationRepository.findAllByUserIdOrderByCreatedAtDesc(user.id ?: 0).take(limit)
}
