package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.service.DocumentService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/approval")
class ApprovalController(private val documentService: DocumentService) {

    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("documents", documentService.findAwaitingDecision())
        return "approval/list"
    }
}
