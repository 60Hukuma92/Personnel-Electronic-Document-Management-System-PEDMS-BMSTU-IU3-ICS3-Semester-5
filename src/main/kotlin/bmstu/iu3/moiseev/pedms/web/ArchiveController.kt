package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.service.ArchiveService
import bmstu.iu3.moiseev.pedms.service.DocumentService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/archive")
class ArchiveController(
    private val archiveService: ArchiveService,
    private val documentService: DocumentService
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) text: String?,
        @RequestParam(required = false) typeId: Int?,
        model: Model
    ): String {
        archiveService.requireArchiveAccess()
        model.addAttribute("documents", archiveService.findArchive(text, typeId))
        model.addAttribute("types", documentService.findActiveTypes())
        model.addAttribute("text", text ?: "")
        model.addAttribute("selectedTypeId", typeId)
        return "archive/list"
    }

    @PostMapping("/archive-signed")
    fun archiveSigned(redirectAttributes: RedirectAttributes): String {
        val count = archiveService.archiveSignedDocuments()
        redirectAttributes.addFlashAttribute(
            "successMessage",
            "Переведено в архив исполненных документов: $count"
        )
        return "redirect:/archive"
    }
}
