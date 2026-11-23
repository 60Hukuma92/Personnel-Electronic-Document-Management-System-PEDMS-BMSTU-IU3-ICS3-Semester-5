package bmstu.iu3.moiseev.pedms.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/approval")
class ApprovalController {

    @GetMapping
    fun list(): String = "redirect:/documents?status=IN_APPROVAL"
}
