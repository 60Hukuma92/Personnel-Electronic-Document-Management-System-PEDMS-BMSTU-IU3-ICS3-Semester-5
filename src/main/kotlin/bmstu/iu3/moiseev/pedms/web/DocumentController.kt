package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.domain.StepAction
import bmstu.iu3.moiseev.pedms.dto.DocumentForm
import bmstu.iu3.moiseev.pedms.dto.DocumentValidationException
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.repository.DocTypeRepository
import bmstu.iu3.moiseev.pedms.service.DocumentService
import bmstu.iu3.moiseev.pedms.service.OrderFormationService
import bmstu.iu3.moiseev.pedms.service.RouteDraft
import bmstu.iu3.moiseev.pedms.service.RoutingService
import org.springframework.format.annotation.DateTimeFormat
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
import java.time.LocalDate

@Controller
@RequestMapping("/documents")
class DocumentController(
    private val documentService: DocumentService,
    private val docTypeRepository: DocTypeRepository,
    private val routingService: RoutingService,
    private val orderFormationService: OrderFormationService
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) typeId: Int?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) text: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        model: Model
    ): String {
        val selectedStatus = status?.let { value -> DocStatus.entries.firstOrNull { it.name == value } }
        model.addAttribute("documents", documentService.findList(typeId, selectedStatus, text, from, to))
        model.addAttribute("types", documentService.findActiveTypes())
        model.addAttribute("statuses", DocStatus.entries)
        model.addAttribute("selectedTypeId", typeId)
        model.addAttribute("selectedStatus", selectedStatus)
        model.addAttribute("text", text ?: "")
        model.addAttribute("from", from)
        model.addAttribute("to", to)
        return "documents/list"
    }

    @GetMapping("/orders")
    fun orders(model: Model): String {
        model.addAttribute("applications", orderFormationService.availableApplications())
        model.addAttribute("orderReport", null)
        return "documents/orders"
    }

    @PostMapping("/orders/from/{applicationId}")
    fun createOrder(
        @PathVariable applicationId: Int,
        redirectAttributes: RedirectAttributes
    ): String = try {
        val order = orderFormationService.createFromApplication(applicationId)
        redirectAttributes.addFlashAttribute("successMessage", "Проект приказа сформирован из согласованного заявления")
        "redirect:/documents/${order.id}"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/orders"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/orders"
    }

    @PostMapping("/orders/batch")
    fun batchCreateOrders(
        @RequestParam("file") file: MultipartFile,
        @RequestParam(required = false, defaultValue = "false") submitToRoute: Boolean,
        model: Model
    ): String = try {
        val report = orderFormationService.importOrders(file, submitToRoute)
        model.addAttribute("applications", orderFormationService.availableApplications())
        model.addAttribute("orderReport", report)
        "documents/orders"
    } catch (ex: IllegalArgumentException) {
        model.addAttribute("applications", orderFormationService.availableApplications())
        model.addAttribute("orderReport", null)
        model.addAttribute("errorMessage", ex.message)
        "documents/orders"
    }

    @PostMapping("/orders/batch-sign")
    fun batchSignOrders(
        @RequestParam documentIds: List<Int>,
        redirectAttributes: RedirectAttributes
    ): String = try {
        val documents = orderFormationService.submitBatch(documentIds)
        redirectAttributes.addFlashAttribute(
            "successMessage",
            "На единый маршрут подписания отправлено приказов: ${documents.size}"
        )
        "redirect:/documents/orders"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/orders"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/orders"
    }

    @GetMapping("/new")
    fun newForm(
        @RequestParam(required = false) typeId: Int?,
        model: Model
    ): String {
        val type = typeId?.let { docTypeRepository.findById(it).orElse(null) }
            ?: documentService.findActiveTypes().firstOrNull()
        model.addAttribute("form", DocumentForm(typeId = type?.id))
        model.addAttribute("type", type)
        model.addAttribute("types", documentService.findActiveTypes())
        model.addAttribute("editing", false)
        model.addAttribute("fieldErrors", emptyMap<String, String>())
        return "documents/form"
    }

    @PostMapping
    fun create(
        @ModelAttribute("form") form: DocumentForm,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String = try {
        val document = documentService.create(form)
        redirectAttributes.addFlashAttribute("successMessage", "Черновик документа сохранён")
        "redirect:/documents/${document.id}"
    } catch (ex: DocumentValidationException) {
        fillFormModel(model, form, ex.fieldErrors, false)
        "documents/form"
    }

    @GetMapping("/{id}")
    fun card(@PathVariable id: Int, model: Model): String {
        val document = documentService.findById(id)
            ?: return "redirect:/documents"
        model.addAttribute("document", document)
        model.addAttribute("attachments", documentService.attachments(id))
        model.addAttribute("routeSteps", routingService.previewRoute(id))
        model.addAttribute("routeHistory", routingService.findHistory(id))
        model.addAttribute("availableApprovers", routingService.availableApprovers())
        model.addAttribute("canActOnRoute", routingService.canAct(id))
        return "documents/card"
    }

    @PostMapping("/{id}/submit")
    fun submit(
        @PathVariable id: Int,
        redirectAttributes: RedirectAttributes
    ): String = try {
        routingService.submit(id)
        redirectAttributes.addFlashAttribute("successMessage", "Документ отправлен на согласование")
        "redirect:/documents/$id"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    }

    @PostMapping("/{id}/route")
    fun saveRoute(
        @PathVariable id: Int,
        @RequestParam approverIds: List<Int>,
        @RequestParam actions: List<String>,
        @RequestParam deadlineDays: List<Int>,
        redirectAttributes: RedirectAttributes
    ): String = try {
        if (approverIds.size != actions.size || approverIds.size != deadlineDays.size) {
            throw IllegalArgumentException("Некорректные данные маршрута")
        }
        val drafts = approverIds.indices.map { index ->
            RouteDraft(
                userId = approverIds[index],
                action = StepAction.entries.firstOrNull { it.name == actions[index] }
                    ?: throw IllegalArgumentException("Некорректное действие этапа"),
                deadlineDays = deadlineDays[index],
                stepOrder = index + 1
            )
        }
        routingService.saveRoute(id, drafts)
        redirectAttributes.addFlashAttribute("successMessage", "Маршрут скорректирован")
        "redirect:/documents/$id"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    }

    @PostMapping("/{id}/approval/approve")
    fun approve(
        @PathVariable id: Int,
        @RequestParam(required = false) comment: String?,
        redirectAttributes: RedirectAttributes
    ): String = try {
        routingService.approve(id, comment)
        redirectAttributes.addFlashAttribute("successMessage", "Этап согласования выполнен")
        "redirect:/documents/$id"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    } catch (ex: org.springframework.security.access.AccessDeniedException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    }

    @PostMapping("/{id}/approval/return")
    fun returnForRework(
        @PathVariable id: Int,
        @RequestParam comment: String,
        redirectAttributes: RedirectAttributes
    ): String = try {
        routingService.returnForRework(id, comment)
        redirectAttributes.addFlashAttribute("successMessage", "Документ возвращён на доработку")
        "redirect:/documents/$id"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    }

    @PostMapping("/{id}/approval/reject")
    fun reject(
        @PathVariable id: Int,
        @RequestParam comment: String,
        redirectAttributes: RedirectAttributes
    ): String = try {
        routingService.reject(id, comment)
        redirectAttributes.addFlashAttribute("successMessage", "Документ отклонён")
        "redirect:/documents/$id"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    }

    @PostMapping("/{id}/approval/delegate")
    fun delegate(
        @PathVariable id: Int,
        @RequestParam targetUserId: Int,
        @RequestParam(required = false) comment: String?,
        redirectAttributes: RedirectAttributes
    ): String = try {
        routingService.delegate(id, targetUserId, comment)
        redirectAttributes.addFlashAttribute("successMessage", "Этап делегирован")
        "redirect:/documents/$id"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    }

    @GetMapping("/{id}/edit")
    fun editForm(@PathVariable id: Int, model: Model): String {
        val document = documentService.findById(id) ?: return "redirect:/documents"
        if (!document.status.isEditable) return "redirect:/documents/$id"
        val form = DocumentForm(
            typeId = document.type?.id,
            title = document.title,
            periodStart = document.periodStart,
            periodEnd = document.periodEnd,
            fields = document.contentJson.entries
                .filter { it.value != null }
                .associateTo(linkedMapOf()) { it.key to it.value.toString() }
        )
        model.addAttribute("form", form)
        model.addAttribute("type", document.type)
        model.addAttribute("types", documentService.findActiveTypes())
        model.addAttribute("editing", true)
        model.addAttribute("documentId", id)
        model.addAttribute("fieldErrors", emptyMap<String, String>())
        return "documents/form"
    }

    @PostMapping("/{id}/edit")
    fun update(
        @PathVariable id: Int,
        @ModelAttribute("form") form: DocumentForm,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String = try {
        documentService.update(id, form)
        redirectAttributes.addFlashAttribute("successMessage", "Изменения документа сохранены")
        "redirect:/documents/$id"
    } catch (ex: DocumentValidationException) {
        fillFormModel(model, form, ex.fieldErrors, true, id)
        "documents/form"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    }

    @PostMapping("/{id}/recall")
    fun recall(
        @PathVariable id: Int,
        redirectAttributes: RedirectAttributes
    ): String = try {
        documentService.recall(id)
        redirectAttributes.addFlashAttribute("successMessage", "Документ отозван")
        "redirect:/documents/$id"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    }

    @PostMapping("/{id}/attachments")
    fun attachment(
        @PathVariable id: Int,
        @RequestParam("file") file: MultipartFile,
        redirectAttributes: RedirectAttributes
    ): String = try {
        documentService.addAttachment(id, file)
        redirectAttributes.addFlashAttribute("successMessage", "Вложение загружено")
        "redirect:/documents/$id"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/documents/$id"
    }

    private fun fillFormModel(
        model: Model,
        form: DocumentForm,
        fieldErrors: Map<String, String>,
        editing: Boolean,
        documentId: Int? = null
    ) {
        val type = form.typeId?.let { docTypeRepository.findById(it).orElse(null) }
        model.addAttribute("form", form)
        model.addAttribute("type", type)
        model.addAttribute("types", documentService.findActiveTypes())
        model.addAttribute("editing", editing)
        model.addAttribute("documentId", documentId)
        model.addAttribute("fieldErrors", fieldErrors)
    }
}
