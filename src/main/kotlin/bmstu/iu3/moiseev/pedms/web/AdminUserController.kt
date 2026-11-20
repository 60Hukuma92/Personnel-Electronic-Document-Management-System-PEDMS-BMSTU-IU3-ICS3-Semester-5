package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.domain.UserStatus
import bmstu.iu3.moiseev.pedms.dto.ImportReport
import bmstu.iu3.moiseev.pedms.dto.UserForm
import bmstu.iu3.moiseev.pedms.dto.UserValidationException
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.repository.PositionRepository
import bmstu.iu3.moiseev.pedms.service.UserImportService
import bmstu.iu3.moiseev.pedms.service.UserManagementService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.nio.charset.StandardCharsets

/**
 * Управление учётными записями пользователей (UC040, FR010, FR020, FR190).
 *
 * Раздел доступен только администратору — ограничение задано в SecurityConfig
 * для пути /admin.
 */
@Controller
@RequestMapping("/admin/users")
class AdminUserController(
    private val userManagementService: UserManagementService,
    private val userImportService: UserImportService,
    private val departmentRepository: DepartmentRepository,
    private val positionRepository: PositionRepository
) {

    // ------------------------------------------------------------------
    // Реестр учётных записей
    // ------------------------------------------------------------------

    /** Реестр учётных записей с поиском и фильтрами. */
    @GetMapping
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) departmentId: Int?,
        model: Model
    ): String {
        val selectedRole = role?.let { value -> UserRole.entries.firstOrNull { it.name == value } }
        val selectedStatus = status?.let { value -> UserStatus.entries.firstOrNull { it.name == value } }

        model.addAttribute("users", userManagementService.findUsers(query, selectedRole, selectedStatus, departmentId))
        model.addAttribute("query", query ?: "")
        model.addAttribute("selectedRole", selectedRole)
        model.addAttribute("selectedStatus", selectedStatus)
        model.addAttribute("selectedDepartmentId", departmentId)
        model.addAttribute("roles", UserRole.entries)
        model.addAttribute("statuses", UserStatus.entries)
        model.addAttribute("departments", departmentRepository.findAll().sortedBy { it.path })
        model.addAttribute("statusCounts", userManagementService.countByStatus())
        model.addAttribute("roleCounts", userManagementService.countByRole())
        return "admin/users/list"
    }

    // ------------------------------------------------------------------
    // Создание и изменение
    // ------------------------------------------------------------------

    /** Форма создания учётной записи. */
    @GetMapping("/new")
    fun newForm(model: Model): String {
        model.addAttribute("form", UserForm())
        model.addAttribute("editing", false)
        fillReferences(model)
        return "admin/users/form"
    }

    /** Создание учётной записи (FR010). */
    @PostMapping("/new")
    fun create(
        @ModelAttribute("form") form: UserForm,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        return try {
            val created = userManagementService.createUser(form)
            redirectAttributes.addFlashAttribute(
                "successMessage",
                "Учётная запись «${created.user.fullName}» создана"
            )
            redirectAttributes.addFlashAttribute("temporaryPassword", created.password)
            redirectAttributes.addFlashAttribute(
                "temporaryPasswordLogin",
                created.user.email
            )
            "redirect:/admin/users/${created.user.id}"
        } catch (ex: UserValidationException) {
            model.addAttribute("form", form)
            model.addAttribute("editing", false)
            model.addAttribute("fieldErrors", ex.fieldErrors)
            fillReferences(model)
            "admin/users/form"
        }
    }

    /** Форма изменения сведений об учётной записи. */
    @GetMapping("/{id}/edit")
    fun editForm(@PathVariable id: Int, model: Model): String {
        val user = userManagementService.findById(id) ?: return "redirect:/admin/users"
        model.addAttribute("form", toForm(user))
        model.addAttribute("editing", true)
        model.addAttribute("userId", id)
        fillReferences(model)
        return "admin/users/form"
    }

    /** Сохранение изменённых сведений (FR190). */
    @PostMapping("/{id}/edit")
    fun update(
        @PathVariable id: Int,
        @ModelAttribute("form") form: UserForm,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        return try {
            val user = userManagementService.updateUser(id, form)
            redirectAttributes.addFlashAttribute(
                "successMessage",
                "Сведения об учётной записи «${user.fullName}» сохранены"
            )
            "redirect:/admin/users/$id"
        } catch (ex: UserValidationException) {
            model.addAttribute("form", form)
            model.addAttribute("editing", true)
            model.addAttribute("userId", id)
            model.addAttribute("fieldErrors", ex.fieldErrors)
            fillReferences(model)
            "admin/users/form"
        }
    }

    // ------------------------------------------------------------------
    // Карточка учётной записи и жизненный цикл (FR020)
    // ------------------------------------------------------------------

    /** Карточка учётной записи с полными сведениями (FR190). */
    @GetMapping("/{id}")
    fun card(@PathVariable id: Int, model: Model): String {
        val user = userManagementService.findById(id) ?: return "redirect:/admin/users"
        model.addAttribute("user", user)
        model.addAttribute("roles", UserRole.entries)
        model.addAttribute("statuses", UserStatus.entries)
        return "admin/users/card"
    }

    /** Блокировка учётной записи. */
    @PostMapping("/{id}/block")
    fun block(@PathVariable id: Int, redirectAttributes: RedirectAttributes): String {
        userManagementService.block(id)
        redirectAttributes.addFlashAttribute("successMessage", "Учётная запись заблокирована")
        return "redirect:/admin/users/$id"
    }

    /** Разблокировка учётной записи. */
    @PostMapping("/{id}/unblock")
    fun unblock(@PathVariable id: Int, redirectAttributes: RedirectAttributes): String {
        userManagementService.unblock(id)
        redirectAttributes.addFlashAttribute("successMessage", "Учётная запись разблокирована")
        return "redirect:/admin/users/$id"
    }

    /** Активация учётной записи. */
    @PostMapping("/{id}/activate")
    fun activate(@PathVariable id: Int, redirectAttributes: RedirectAttributes): String {
        userManagementService.activate(id)
        redirectAttributes.addFlashAttribute("successMessage", "Учётная запись активирована")
        return "redirect:/admin/users/$id"
    }

    /** Удаление с обезличиванием персональных данных (FR020). */
    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: Int, redirectAttributes: RedirectAttributes): String {
        userManagementService.deleteUser(id)
        redirectAttributes.addFlashAttribute(
            "successMessage",
            "Учётная запись удалена, персональные данные обезличены"
        )
        return "redirect:/admin/users"
    }

    /** Сброс пароля — система формирует временный пароль. */
    @PostMapping("/{id}/reset-password")
    fun resetPassword(@PathVariable id: Int, redirectAttributes: RedirectAttributes): String {
        val password = userManagementService.resetPassword(id)
        redirectAttributes.addFlashAttribute("temporaryPassword", password)
        redirectAttributes.addFlashAttribute(
            "temporaryPasswordLogin",
            userManagementService.findById(id)?.email ?: ""
        )
        redirectAttributes.addFlashAttribute("successMessage", "Пароль сброшен")
        return "redirect:/admin/users/$id"
    }

    // ------------------------------------------------------------------
    // Загрузка учётных записей из файла (FR010)
    // ------------------------------------------------------------------

    /** Форма загрузки учётных записей из файла. */
    @GetMapping("/import")
    fun importForm(model: Model): String {
        model.addAttribute("report", null as ImportReport?)
        return "admin/users/import"
    }

    /** Загрузка файла XLSX или CSV. */
    @PostMapping("/import")
    fun importFile(
        @RequestParam("file") file: MultipartFile,
        model: Model
    ): String {
        val report = try {
            userImportService.import(file)
        } catch (ex: IllegalArgumentException) {
            model.addAttribute("importError", ex.message)
            model.addAttribute("report", null as ImportReport?)
            return "admin/users/import"
        }
        model.addAttribute("report", report)
        model.addAttribute("importError", null as String?)
        return "admin/users/import"
    }

    /** Шаблон файла загрузки. */
    @GetMapping("/import/template")
    fun importTemplate(response: HttpServletResponse) {
        val bytes = userImportService.templateCsv()
        response.contentType = "text/csv; charset=UTF-8"
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users-template.csv\"")
        response.outputStream.write(bytes)
        response.outputStream.flush()
    }

    // ------------------------------------------------------------------
    // Вспомогательные методы
    // ------------------------------------------------------------------

    /** Справочники подразделений и должностей для элементов формы. */
    private fun fillReferences(model: Model) {
        model.addAttribute("departments", departmentRepository.findAll().sortedBy { it.path })
        model.addAttribute("positions", positionRepository.findAllByOrderByNameAsc())
        model.addAttribute("roles", UserRole.entries)
        model.addAttribute("statuses", UserStatus.entries)
    }

    /** Заполнение формы по данным учётной записи. */
    private fun toForm(user: bmstu.iu3.moiseev.pedms.entity.AppUserEntity): UserForm = UserForm(
        fullName = user.fullName,
        email = user.email,
        personnelNumber = user.personnelNumber.orEmpty(),
        inn = user.inn.orEmpty(),
        snils = user.snils.orEmpty(),
        phone = user.phone.orEmpty(),
        dateOfBirth = user.dateOfBirth?.toString().orEmpty(),
        departmentId = user.department?.id?.toString().orEmpty(),
        positionId = user.position?.id?.toString().orEmpty(),
        role = user.role.name,
        status = user.status.name,
        password = ""
    )
}
