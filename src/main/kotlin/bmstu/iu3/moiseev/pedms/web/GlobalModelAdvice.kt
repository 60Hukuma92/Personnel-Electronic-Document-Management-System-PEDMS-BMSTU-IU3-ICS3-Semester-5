package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.repository.NotificationRepository
import bmstu.iu3.moiseev.pedms.service.CurrentUserService
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Общие данные для всех страниц интерфейса.
 *
 * В шапку каждой страницы выводятся данные текущего пользователя и счётчик
 * непрочитанных уведомлений (FR130) — это соответствует макету «Мой кабинет»
 * (рисунок 2.4 записки).
 */
@ControllerAdvice
class GlobalModelAdvice(
    private val currentUserService: CurrentUserService,
    private val notificationRepository: NotificationRepository
) {

    /** Текущий пользователь системы. */
    @ModelAttribute("currentUser")
    fun currentUser(): AppUserEntity? = currentUserService.currentUser()

    /** Количество непрочитанных уведомлений текущего пользователя. */
    @ModelAttribute("unreadCount")
    fun unreadCount(): Long =
        currentUserService.currentUser()?.id?.let { notificationRepository.countUnread(it) } ?: 0L
}
