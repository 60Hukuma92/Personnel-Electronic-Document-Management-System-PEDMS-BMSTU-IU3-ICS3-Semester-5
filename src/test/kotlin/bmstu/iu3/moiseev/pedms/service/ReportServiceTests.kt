package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.domain.StepStatus
import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DocTypeEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.entity.RouteStepEntity
import bmstu.iu3.moiseev.pedms.repository.AcquaintanceMarkRepository
import bmstu.iu3.moiseev.pedms.repository.AcquaintanceSheetRepository
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import bmstu.iu3.moiseev.pedms.repository.RouteStepRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

class ReportServiceTests {
    private lateinit var documentRepository: DocumentRepository
    private lateinit var routeStepRepository: RouteStepRepository
    private lateinit var acquaintanceSheetRepository: AcquaintanceSheetRepository
    private lateinit var acquaintanceMarkRepository: AcquaintanceMarkRepository
    private lateinit var appUserRepository: AppUserRepository
    private lateinit var departmentRepository: DepartmentRepository
    private lateinit var currentUserService: CurrentUserService
    private lateinit var service: ReportService

    @BeforeEach
    fun setUp() {
        documentRepository = mock(DocumentRepository::class.java)
        routeStepRepository = mock(RouteStepRepository::class.java)
        acquaintanceSheetRepository = mock(AcquaintanceSheetRepository::class.java)
        acquaintanceMarkRepository = mock(AcquaintanceMarkRepository::class.java)
        appUserRepository = mock(AppUserRepository::class.java)
        departmentRepository = mock(DepartmentRepository::class.java)
        currentUserService = mock(CurrentUserService::class.java)
        `when`(currentUserService.requireCurrentUser()).thenReturn(
            AppUserEntity(id = 1, fullName = "Сотрудник ОК", email = "hr@pedms.ru", role = UserRole.HR)
        )
        service = ReportService(
            documentRepository, routeStepRepository, acquaintanceSheetRepository,
            acquaintanceMarkRepository, appUserRepository, departmentRepository, currentUserService
        )
    }

    @Test
    fun `document registry applies period and returns totals`() {
        val author = AppUserEntity(id = 10, fullName = "Иванов И.И.", email = "ivanov@pedms.ru")
        val document = DocumentEntity(
            id = 100, type = DocTypeEntity(name = "Заявление"), author = author,
            number = "ЗАЯВ-1", title = "Отпуск", status = DocStatus.SIGNED,
            createdAt = LocalDateTime.of(2026, 5, 10, 10, 0),
            periodStart = LocalDate.of(2026, 6, 1),
            periodEnd = LocalDate.of(2026, 6, 10)
        )
        `when`(documentRepository.findAllByOrderByCreatedAtDesc()).thenReturn(listOf(document))

        val result = service.build(
            ReportService.ReportKind.DOCUMENT_REGISTRY,
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 31)
        )

        assertEquals(1, result.rows.size)
        assertTrue(result.summary.contains("Всего документов: 1"))
    }

    @Test
    fun `reverse period is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.build(
                ReportService.ReportKind.DOCUMENT_REGISTRY,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 5, 1)
            )
        }
    }

    @Test
    fun `overdue report includes deadline and document status`() {
        val document = DocumentEntity(
            id = 200, number = "ПРИК-2", title = "Приказ",
            status = DocStatus.IN_APPROVAL, createdAt = LocalDateTime.now().minusDays(10)
        )
        val approver = AppUserEntity(id = 20, fullName = "Петров П.П.", email = "petrov@pedms.ru")
        val step = RouteStepEntity(
            id = 300, document = document, user = approver, stepOrder = 1,
            status = StepStatus.PENDING, deadline = LocalDateTime.now().minusDays(2)
        )
        val matcherNow: LocalDateTime =
            org.mockito.ArgumentMatchers.any(LocalDateTime::class.java) ?: LocalDateTime.now()
        `when`(routeStepRepository.findOverdue(matcherNow))
            .thenReturn(listOf(step))

        val result = service.build(ReportService.ReportKind.OVERDUE, null, null)

        assertEquals(1, result.rows.size)
        assertEquals("ПРИК-2", result.rows[0][0])
    }

    @Test
    fun `vacation forecast has confidence interval fields`() {
        val user = AppUserEntity(id = 7, fullName = "Сидоров С.С.", email = "sidorov@pedms.ru")
        `when`(appUserRepository.findById(7)).thenReturn(Optional.of(user))
        `when`(documentRepository.findAllByAuthorIdOrderByCreatedAtDesc(7)).thenReturn(emptyList())

        val result = service.build(ReportService.ReportKind.VACATION_FORECAST, null, null, null, 7)

        assertEquals("Сидоров С.С.", result.rows[0][0])
        assertEquals("95%", result.rows[0][8])
    }

    @Test
    fun `csv export escapes separators and quotes`() {
        val report = ReportService.ReportResult(
            "Проверка", listOf("Название", "Комментарий"),
            listOf(listOf("Документ", "а;б,\"текст\"")), "Итого: 1"
        )
        val csv = String(service.exportCsv(report), Charsets.UTF_8)
        assertTrue(csv.contains("\"а;б,\"\"текст\"\"\""))
    }

    @Test
    fun `xlsx export creates non-empty workbook`() {
        val report = ReportService.ReportResult(
            "Проверка", listOf("Колонка"), listOf(listOf("Значение")), "Итого: 1"
        )
        assertTrue(service.exportXlsx(report).isNotEmpty())
    }
}
