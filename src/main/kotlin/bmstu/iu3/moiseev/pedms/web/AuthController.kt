package bmstu.iu3.moiseev.pedms.web

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Форма входа в систему (UC030).
 */
@Controller
class AuthController {

    /**
     * Страница входа.
     *
     * Параметры error и logout выставляются Spring Security:
     *   • error — неверный логин или пароль;
     *   • logout — выход выполнен успешно.
     */
    @GetMapping("/login")
    fun loginPage(
        @RequestParam(required = false) error: String?,
        @RequestParam(required = false) logout: String?,
        model: Model
    ): String {
        model.addAttribute("loginError", error != null)
        model.addAttribute("logoutSuccess", logout != null)
        return "login"
    }
}
