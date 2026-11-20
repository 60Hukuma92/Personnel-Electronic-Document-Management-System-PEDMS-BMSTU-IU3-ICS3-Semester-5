package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.domain.NotificationEvent
import bmstu.iu3.moiseev.pedms.entity.UserSettingsEntity
import bmstu.iu3.moiseev.pedms.repository.UserSettingsRepository
import bmstu.iu3.moiseev.pedms.service.CurrentUserService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class SettingsController(
    private val currentUserService: CurrentUserService,
    private val settingsRepository: UserSettingsRepository
) {
    @GetMapping("/settings")
    fun form(model: Model): String {
        val user = currentUserService.requireCurrentUser()
        val settings = settingsRepository.findByUserId(user.id ?: 0)
            ?: UserSettingsEntity(userId = user.id)
        model.addAttribute("settings", settings)
        model.addAttribute("events", NotificationEvent.entries)
        model.addAttribute(
            "selectedEvents",
            settings.notifEventTypes.split(',').map(String::trim).filter(String::isNotBlank).toSet()
        )
        return "settings"
    }

    @PostMapping("/settings")
    fun save(
        @RequestParam(required = false) events: List<String>?,
        @RequestParam(defaultValue = "false") webEnabled: Boolean,
        @RequestParam(defaultValue = "false") emailEnabled: Boolean,
        redirectAttributes: RedirectAttributes
    ): String {
        val user = currentUserService.requireCurrentUser()
        val settings = settingsRepository.findByUserId(user.id ?: 0)
            ?: UserSettingsEntity(userId = user.id)
        settings.notifEventTypes = events.orEmpty()
            .filter { value -> NotificationEvent.entries.any { it.name == value } }
            .distinct()
            .joinToString(",")
        settings.notifWebEnabled = webEnabled
        settings.notifEmailEnabled = emailEnabled
        settingsRepository.save(settings)
        redirectAttributes.addFlashAttribute("successMessage", "Настройки уведомлений сохранены")
        return "redirect:/settings"
    }
}
