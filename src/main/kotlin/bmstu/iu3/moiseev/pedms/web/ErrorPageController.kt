package bmstu.iu3.moiseev.pedms.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping

/**
 * Обработка ошибок с выводом собственных страниц.
 *
 * Вместо стандартного технического отчёта Spring Boot пользователю
 * показывается понятная страница с объяснением и ссылкой
 * в личный кабинет.
 */
@Controller
class ErrorPageController : ErrorController {

    @RequestMapping("/error")
    fun handleError(request: HttpServletRequest): String {
        val status = request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: 500
        return when (status) {
            401 -> "error/403"
            403 -> "error/403"
            404 -> "error/404"
            else -> "error/500"
        }
    }
}
