package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.repository.DocTypeRepository
import bmstu.iu3.moiseev.pedms.repository.OrganizationRepository
import bmstu.iu3.moiseev.pedms.repository.PositionRepository
import bmstu.iu3.moiseev.pedms.service.CurrentUserService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Техническая страница этапа 1 и корневое перенаправление.
 *
 * Показывает состояние базы данных и наполненные справочники — это
 * позволяет убедиться, что схема создана правильно и справочники
 * заполнены.
 */
@Controller
class HomeController(
    private val organizationRepository: OrganizationRepository,
    private val departmentRepository: DepartmentRepository,
    private val positionRepository: PositionRepository,
    private val docTypeRepository: DocTypeRepository,
    private val userRepository: AppUserRepository,
    private val currentUserService: CurrentUserService
) {

    /**
     * Корневая страница: перенаправляет в личный кабинет
     * авторизованного пользователя и на форму входа — неавторизованного.
     */
    @GetMapping("/")
    fun home(): String {
        val user = currentUserService.currentUser()
        return if (user != null) "redirect:/dashboard" else "redirect:/login"
    }

    /**
     * Техническая страница состояния базы данных.
     *
     * Показывает наполненные справочники — это позволяет убедиться,
     * что схема создана правильно и справочники заполнены.
     */
    @GetMapping("/status")
    fun status(model: Model): String {
        val organization = organizationRepository.findFirstByOrderByIdAsc()

        model.addAttribute("organization", organization)
        model.addAttribute("departments", organization?.id?.let { departmentRepository.findTree(it) } ?: emptyList<Any>())
        model.addAttribute("positions", positionRepository.findAllByOrderByNameAsc())
        model.addAttribute("docTypes", docTypeRepository.findAllByOrderByNameAsc())
        model.addAttribute("userCount", userRepository.count())
        return "home"
    }
}
