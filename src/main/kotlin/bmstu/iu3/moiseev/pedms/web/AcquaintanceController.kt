package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.domain.AudienceType
import bmstu.iu3.moiseev.pedms.dto.AcquaintanceSheetForm
import bmstu.iu3.moiseev.pedms.service.AcquaintanceService
import bmstu.iu3.moiseev.pedms.service.SignatureService
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/acquaintance")
class AcquaintanceController(
    private val acquaintanceService: AcquaintanceService,
    private val signatureService: SignatureService
) {
    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("sheets", acquaintanceService.sheets())
        return "acquaintance/list"
    }

    @GetMapping("/create")
    fun createForm(model: Model): String {
        fillCreationModel(model, AcquaintanceSheetForm())
        return "acquaintance/create"
    }

    @PostMapping("/preview")
    fun preview(
        @ModelAttribute form: AcquaintanceSheetForm,
        model: Model
    ): String = try {
        model.addAttribute("preview", acquaintanceService.preview(form))
        fillCreationModel(model, form)
        "acquaintance/create"
    } catch (ex: IllegalArgumentException) {
        fillCreationModel(model, form)
        model.addAttribute("errorMessage", ex.message)
        "acquaintance/create"
    }

    @PostMapping
    fun create(
        @ModelAttribute form: AcquaintanceSheetForm,
        redirectAttributes: RedirectAttributes
    ): String = try {
        val sheet = acquaintanceService.create(form)
        redirectAttributes.addFlashAttribute("successMessage", "Лист ознакомления создан")
        "redirect:/acquaintance/${sheet.id}"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/acquaintance/create"
    }

    @GetMapping("/{id}")
    fun card(@PathVariable id: Int, model: Model): String {
        val sheet = acquaintanceService.findSheet(id)
        model.addAttribute("sheet", sheet)
        model.addAttribute("marks", acquaintanceService.marks(id))
        model.addAttribute("summary", acquaintanceService.summary(id))
        return "acquaintance/card"
    }

    @GetMapping("/{id}/read")
    fun read(@PathVariable id: Int, model: Model): String {
        val sheet = acquaintanceService.findSheet(id)
        val mark = acquaintanceService.markForCurrentUser(id)
            ?: return "redirect:/acquaintance"
        model.addAttribute("sheet", sheet)
        model.addAttribute("mark", mark)
        model.addAttribute("confirmationCode", signatureService.generateCode())
        return "acquaintance/read"
    }

    @PostMapping("/{id}/acknowledge")
    fun acknowledge(
        @PathVariable id: Int,
        @RequestParam expectedCode: String,
        @RequestParam code: String,
        redirectAttributes: RedirectAttributes
    ): String = try {
        acquaintanceService.acknowledge(id, expectedCode, code)
        redirectAttributes.addFlashAttribute("successMessage", "Ознакомление подтверждено простой ЭП")
        "redirect:/acquaintance/$id"
    } catch (ex: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/acquaintance/$id/read"
    } catch (ex: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", ex.message)
        "redirect:/acquaintance/$id"
    }

    @GetMapping("/{id}/pending.csv")
    fun pending(@PathVariable id: Int): ResponseEntity<ByteArray> {
        val filename = "acquaintance-$id-pending.csv"
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename).build().toString()
            )
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(acquaintanceService.pendingCsv(id))
    }

    private fun fillCreationModel(model: Model, form: AcquaintanceSheetForm) {
        model.addAttribute("form", form)
        model.addAttribute("documents", acquaintanceService.documentsForCreation())
        model.addAttribute("departments", acquaintanceService.departments())
        model.addAttribute("positions", acquaintanceService.positions())
        model.addAttribute("audienceTypes", AudienceType.entries)
    }
}
