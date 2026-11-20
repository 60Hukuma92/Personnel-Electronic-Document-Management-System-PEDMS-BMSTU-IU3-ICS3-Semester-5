package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.service.ReportService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDate

@Controller
@RequestMapping("/reports")
class ReportController(
    private val reportService: ReportService,
    private val departmentRepository: DepartmentRepository,
    private val appUserRepository: AppUserRepository
) {
    @GetMapping
    fun reports(
        @RequestParam(required = false) kind: ReportService.ReportKind?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) departmentId: Int?,
        @RequestParam(required = false) userId: Int?,
        model: Model,
        redirectAttributes: RedirectAttributes
    ): String {
        reportService.requireReportAccess()
        fillModel(model, kind, from, to, departmentId, userId)
        if (kind != null) {
            try {
                model.addAttribute("report",
                    reportService.build(kind, from, to, departmentId, userId))
            } catch (ex: IllegalArgumentException) {
                redirectAttributes.addFlashAttribute("errorMessage", ex.message)
            }
        }
        return "reports/list"
    }

    @GetMapping("/export")
    fun export(
        @RequestParam kind: ReportService.ReportKind,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) departmentId: Int?,
        @RequestParam(required = false) userId: Int?,
        @RequestParam format: String
    ): ResponseEntity<ByteArray> {
        val result = reportService.build(kind, from, to, departmentId, userId)
        return if (format.lowercase() == "csv") {
            val filename = "report-${kind.name.lowercase()}.csv"
            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(reportService.exportCsv(result))
        } else {
            val filename = "report-${kind.name.lowercase()}.xlsx"
            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
                .contentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(reportService.exportXlsx(result))
        }
    }

    private fun fillModel(model: Model, kind: ReportService.ReportKind?, from: LocalDate?,
                          to: LocalDate?, departmentId: Int?, userId: Int?) {
        model.addAttribute("kinds", ReportService.ReportKind.entries)
        model.addAttribute("departments", departmentRepository.findAll().sortedBy { it.path })
        model.addAttribute("users", appUserRepository.findAllByOrderByFullNameAsc())
        model.addAttribute("selectedKind", kind)
        model.addAttribute("from", from)
        model.addAttribute("to", to)
        model.addAttribute("selectedDepartmentId", departmentId)
        model.addAttribute("selectedUserId", userId)
    }
}
