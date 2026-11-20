package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.repository.NotificationRepository
import bmstu.iu3.moiseev.pedms.service.CurrentUserService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Список уведомлений пользователя (FR130).
 *
 * Непрочитанные уведомления отмечаются прочитанными при открытии списка.
 */
@Controller
class NotificationController(
    private val currentUserService: CurrentUserService,
    private val notificationRepository: NotificationRepository
) {

    @GetMapping("/notifications")
    fun list(model: Model): String {
        val user = currentUserService.requireCurrentUser()
        val notifications = notificationRepository.findAllByUserIdOrderByCreatedAtDesc(user.id ?: 0)
        model.addAttribute("notifications", notifications)
        return "notifications"
    }

    /** Отметка уведомления прочитанным. */
    @PostMapping("/notifications/{id}/read")
    fun markRead(@org.springframework.web.bind.annotation.PathVariable id: Int): String {
        val user = currentUserService.requireCurrentUser()
        notificationRepository.findById(id)
            .filter { it.user?.id == user.id }
            .ifPresent {
                it.readAt = java.time.LocalDateTime.now()
                notificationRepository.save(it)
            }
        return "redirect:/notifications"
    }
}
