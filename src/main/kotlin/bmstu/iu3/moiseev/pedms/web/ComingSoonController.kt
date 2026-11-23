package bmstu.iu3.moiseev.pedms.web

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Осмысленные заглушки разделов, реализованных на последующих этапах НИРС.
 *
 * Каждая ссылка главного меню должна вести на понятную страницу, а не на
 * стандартную 404 или внутреннюю ошибку сервера.
 */
@Controller
class ComingSoonController {

    @GetMapping("/approval")
    fun approval(model: Model): String =
        render(model, "Согласование", "Этап 5", "Маршрутизация и согласование документов")

    @GetMapping("/acquaintance")
    fun acquaintance(model: Model): String =
        render(model, "Ознакомление", "Этап 7", "Ознакомление с ЛНА и простая электронная подпись")

    @GetMapping("/references")
    fun references(model: Model): String =
        render(model, "Справочники", "Этап 3–10", "Справочники видов документов, подразделений и должностей")

    @GetMapping("/reports")
    fun reports(model: Model): String =
        render(model, "Отчёты", "Этап 10", "Отчёты, аналитика и функции базы данных")

    @GetMapping("/settings")
    fun settings(model: Model): String =
        render(model, "Настройки", "Этап 8", "Настройки уведомлений и параметров учётной записи")

    private fun render(model: Model, title: String, stage: String, description: String): String {
        model.addAttribute("pageTitle", title)
        model.addAttribute("stage", stage)
        model.addAttribute("featureDescription", description)
        return "coming-soon"
    }
}
