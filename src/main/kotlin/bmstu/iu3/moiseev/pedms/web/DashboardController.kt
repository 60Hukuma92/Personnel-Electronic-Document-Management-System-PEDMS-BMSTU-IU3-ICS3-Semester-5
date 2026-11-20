package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.service.CurrentUserService
import bmstu.iu3.moiseev.pedms.service.DashboardService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Личный кабинет пользователя (UC030).
 *
 * Соответствует макету «Мой кабинет» (рисунок 2.4 записки):
 * приветствие, виджеты «Мои документы», «Требуют ознакомления»,
 * «Быстрые действия» и список последних уведомлений.
 */
@Controller
class DashboardController(
    private val currentUserService: CurrentUserService,
    private val dashboardService: DashboardService
) {

    @GetMapping("/dashboard")
    fun dashboard(model: Model): String {
        val user = currentUserService.requireCurrentUser()
        model.addAttribute("user", user)
        model.addAttribute("summary", dashboardService.summary(user))
        model.addAttribute("notifications", dashboardService.lastNotifications(user))
        return "dashboard"
    }
}
