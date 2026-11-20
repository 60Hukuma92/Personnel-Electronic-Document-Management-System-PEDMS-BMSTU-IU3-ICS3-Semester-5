package bmstu.iu3.moiseev.pedms.web

import bmstu.iu3.moiseev.pedms.service.AuditService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/documents")
class AuditController(private val auditService: AuditService) {
    @GetMapping("/{id}/history/export")
    fun exportHistory(@PathVariable id: Int): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-history-$id.xlsx\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(auditService.exportHistory(id))
}
