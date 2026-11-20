package bmstu.iu3.moiseev.pedms.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Обработка ошибок с выводом собственных страниц.
 *
 * Вместо стандартного технического отчёта Spring Boot пользователю
 * показывается понятная страница с объяснением и ссылкой
 * в личный кабинет.
 *
 * Отдельные адреса /error/403, /error/404 и /error/500 нужны потому,
 * что на них ссылается настройка разграничения прав (SecurityConfig):
 * при отказе в доступе выполняется перенаправление на страницу 403.
 */
@Controller
class ErrorPageController : ErrorController {

    /** Диспетчер ошибок контейнера: код ответа берётся из атрибута запроса. */
    @RequestMapping("/error")
    fun handleError(request: HttpServletRequest, response: HttpServletResponse): String {
        val status = request.getAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: 500
        response.status = status
        return pageFor(status)
    }

    /** Отказ в доступе (FR140). */
    @GetMapping("/error/403")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun forbidden(): String = "error/403"

    /** Страница не найдена. */
    @GetMapping("/error/404")
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(): String = "error/404"

    /** Внутренняя ошибка сервера. */
    @GetMapping("/error/500")
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun serverError(): String = "error/500"

    private fun pageFor(status: Int): String = when (status) {
        401, 403 -> "error/403"
        404 -> "error/404"
        else -> "error/500"
    }
}
