package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.MarkStatus
import bmstu.iu3.moiseev.pedms.domain.StepStatus
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.entity.RouteStepEntity
import bmstu.iu3.moiseev.pedms.repository.AcquaintanceMarkRepository
import bmstu.iu3.moiseev.pedms.repository.AcquaintanceSheetRepository
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import bmstu.iu3.moiseev.pedms.repository.RouteStepRepository
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

@Service
class ReportService(
    private val documentRepository: DocumentRepository,
    private val routeStepRepository: RouteStepRepository,
    private val acquaintanceSheetRepository: AcquaintanceSheetRepository,
    private val acquaintanceMarkRepository: AcquaintanceMarkRepository,
    private val appUserRepository: AppUserRepository,
    private val departmentRepository: DepartmentRepository,
    private val currentUserService: CurrentUserService
) {
    enum class ReportKind(val title: String) {
        DOCUMENT_REGISTRY("Реестр документов"),
        APPROVAL_DEADLINES("Сроки согласования"),
        APPROVER_LOAD("Нагрузка согласующих"),
        OVERDUE("Просроченные документы"),
        ACQUAINTANCE("Ознакомление с ЛНА"),
        BEST_WORST("Три лучших и три худших"),
        VACATION_FORECAST("Прогноз отпуска")
    }

    data class ReportResult(
        val title: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val summary: String
    )

    fun requireReportAccess() {
        val user = currentUserService.requireCurrentUser()
        if (!user.role.isAtLeastHr) {
            throw AccessDeniedException("Просмотр отчётов разрешён сотрудникам ОК и администраторам")
        }
    }

    fun build(kind: ReportKind, from: LocalDate?, to: LocalDate?, departmentId: Int? = null,
              userId: Int? = null): ReportResult {
        requireReportAccess()
        if (from != null && to != null && from.isAfter(to)) {
            throw IllegalArgumentException("Дата начала периода не может быть позже даты окончания")
        }
        return when (kind) {
            ReportKind.DOCUMENT_REGISTRY -> documentRegistry(from, to, departmentId)
            ReportKind.APPROVAL_DEADLINES -> approvalDeadlines(from, to, departmentId)
            ReportKind.APPROVER_LOAD -> approverLoad(from, to, departmentId)
            ReportKind.OVERDUE -> overdue(from, to, departmentId)
            ReportKind.ACQUAINTANCE -> acquaintance(from, to, departmentId)
            ReportKind.BEST_WORST -> bestWorst(from, to)
            ReportKind.VACATION_FORECAST -> vacationForecast(userId)
        }
    }

    fun exportCsv(result: ReportResult): ByteArray =
        buildString {
            append('\uFEFF')
            appendLine(result.headers.joinToString(";") { csvValue(it) })
            result.rows.forEach { row ->
                appendLine(row.joinToString(";") { csvValue(it) })
            }
        }.toByteArray(Charsets.UTF_8)

    fun exportXlsx(result: ReportResult): ByteArray {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet(result.title.take(31))
            val header = sheet.createRow(0)
            result.headers.forEachIndexed { index, title ->
                header.createCell(index).setCellValue(title)
            }
            result.rows.forEachIndexed { rowIndex, values ->
                val row = sheet.createRow(rowIndex + 1)
                values.forEachIndexed { columnIndex, value ->
                    row.createCell(columnIndex).setCellValue(value)
                }
            }
            result.headers.indices.forEach { sheet.autoSizeColumn(it) }
            sheet.createFreezePane(0, 1)
            if (result.summary.isNotBlank()) {
                val summaryRow = sheet.createRow(result.rows.size + 2)
                summaryRow.createCell(0).setCellValue(result.summary)
            }
            return workbookToBytes(workbook)
        }
    }

    private fun documentRegistry(from: LocalDate?, to: LocalDate?, departmentId: Int?): ReportResult {
        val documents = filteredDocuments(from, to).filter { document ->
            departmentId == null || document.author?.department?.id == departmentId
        }
        val headers = listOf("№", "Наименование", "Вид", "Автор", "Подразделение", "Статус", "Создан", "Дней отпуска")
        val rows = documents.map { d ->
            listOf(
                d.number ?: "", d.title, d.type?.name ?: "", d.author?.fullName ?: "",
                d.author?.department?.name ?: "", d.status.title,
                d.createdAt.toLocalDate().toString(), d.vacationDays?.toString() ?: ""
            )
        }
        return ReportResult("Реестр документов", headers, rows,
            "Всего документов: ${rows.size}; подписано: ${documents.count { it.isSigned == true }}")
    }

    private fun approvalDeadlines(from: LocalDate?, to: LocalDate?, departmentId: Int?): ReportResult {
        val steps = filteredSteps(from, to).filter { step ->
            departmentId == null || step.document?.author?.department?.id == departmentId
        }.sortedWith(compareBy<RouteStepEntity> { it.deadline ?: LocalDateTime.MAX }.thenBy { it.stepOrder })
        val headers = listOf("Документ", "Этап", "Согласующий", "Подразделение", "Статус", "Срок", "Решение")
        val rows = steps.map { s ->
            listOf(
                s.document?.number ?: s.document?.title ?: "", s.stepOrder.toString(),
                s.user?.fullName ?: "", s.user?.department?.name ?: "", s.status.title,
                s.deadline?.toString() ?: "", s.decidedAt?.toString() ?: ""
            )
        }
        val overdueCount = steps.count { it.status in setOf(StepStatus.PENDING, StepStatus.IN_PROGRESS) &&
            it.deadline != null && it.deadline!!.isBefore(LocalDateTime.now()) }
        return ReportResult("Сроки согласования", headers, rows, "Этапов: ${rows.size}; просрочено: $overdueCount")
    }

    private fun approverLoad(from: LocalDate?, to: LocalDate?, departmentId: Int?): ReportResult {
        val steps = filteredSteps(from, to).filter { step ->
            departmentId == null || step.user?.department?.id == departmentId
        }
        val grouped = steps.groupBy { it.user?.id }.filterKeys { it != null }
        val headers = listOf("Согласующий", "Подразделение", "Всего этапов", "Принято", "Отклонено", "Средняя задержка, ч")
        val rows = grouped.map { (_, userSteps) ->
            val user = userSteps.firstOrNull()?.user
            val delays = userSteps.mapNotNull { s ->
                if (s.decidedAt != null && s.deadline != null)
                    ChronoUnit.MINUTES.between(s.deadline, s.decidedAt) / 60.0 else null
            }
            val average = if (delays.isEmpty()) 0.0 else delays.average()
            listOf(
                user?.fullName ?: "", user?.department?.name ?: "", userSteps.size.toString(),
                userSteps.count { it.status == StepStatus.APPROVED }.toString(),
                userSteps.count { it.status == StepStatus.REJECTED }.toString(),
                String.format(java.util.Locale.ROOT, "%.2f", average)
            )
        }.sortedByDescending { it[2].toIntOrNull() ?: 0 }
        return ReportResult("Нагрузка согласующих", headers, rows,
            "Согласующих: ${rows.size}; назначенных этапов: ${steps.size}")
    }

    private fun overdue(from: LocalDate?, to: LocalDate?, departmentId: Int?): ReportResult {
        val steps = routeStepRepository.findOverdue(LocalDateTime.now()).filter { step ->
            val inPeriod = (from == null || step.document?.createdAt?.toLocalDate()?.isBefore(from) == false) &&
                (to == null || step.document?.createdAt?.toLocalDate()?.isAfter(to) == false)
            inPeriod && (departmentId == null || step.document?.author?.department?.id == departmentId)
        }.sortedBy { it.deadline }
        val headers = listOf("Документ", "Подразделение", "Согласующий", "Этап", "Срок", "Просрочка, ч", "Статус")
        val rows = steps.map { s ->
            val hours = s.deadline?.let { ChronoUnit.HOURS.between(it, LocalDateTime.now()).toString() } ?: ""
            listOf(
                s.document?.number ?: s.document?.title ?: "",
                s.document?.author?.department?.name ?: "", s.user?.fullName ?: "",
                s.stepOrder.toString(), s.deadline?.toString() ?: "", hours, s.document?.status?.title ?: ""
            )
        }
        return ReportResult("Просроченные документы", headers, rows,
            "Просроченных этапов: ${rows.size}")
    }

    private fun acquaintance(from: LocalDate?, to: LocalDate?, departmentId: Int?): ReportResult {
        val sheets = acquaintanceSheetRepository.findAllByOrderByCreatedAtDesc().filter { sheet ->
            val date = sheet.createdAt.toLocalDate()
            (from == null || !date.isBefore(from)) &&
                (to == null || !date.isAfter(to)) &&
                (departmentId == null || sheet.document?.author?.department?.id == departmentId)
        }
        val headers = listOf("Документ", "Срок ознакомления", "Статус листа", "Ожидается", "Ознакомились", "Осталось", "Процент")
        val rows = sheets.map { s ->
            val pending = s.totalExpected - s.acknowledgedCount
            listOf(
                s.document?.number ?: s.document?.title ?: "", s.deadline?.toString() ?: "",
                s.status.title, s.totalExpected.toString(), s.acknowledgedCount.toString(),
                pending.toString(), "${s.percentComplete}%"
            )
        }
        val acknowledged = sheets.sumOf { it.acknowledgedCount }
        val expected = sheets.sumOf { it.totalExpected }
        return ReportResult("Ознакомление с ЛНА", headers, rows,
            "Листов: ${sheets.size}; ознакомились: $acknowledged из $expected")
    }

    private fun bestWorst(from: LocalDate?, to: LocalDate?): ReportResult {
        val steps = filteredSteps(from, to)
        val completed = steps.filter {
            it.decidedAt != null && it.deadline != null &&
                it.status in setOf(StepStatus.APPROVED, StepStatus.REJECTED)
        }
        val scored = completed.groupBy { it.user?.id }.mapNotNull { (userId, userSteps) ->
            val user = userSteps.firstOrNull()?.user
            if (userId == null || user == null) null
            else {
                val average = userSteps.map { ChronoUnit.MINUTES.between(it.deadline, it.decidedAt) / 60.0 }.average()
                Triple(user.fullName, user.department?.name ?: "", average)
            }
        }
        val headers = listOf("Категория", "Место", "Согласующий", "Подразделение", "Средняя задержка, ч")
        val best = scored.sortedBy { it.third }.take(3)
        val worst = scored.sortedByDescending { it.third }.take(3)
        val rows = mutableListOf<List<String>>()
        best.forEachIndexed { index, value ->
            rows += listOf("ЛУЧШИЕ", (index + 1).toString(), value.first, value.second,
                String.format(java.util.Locale.ROOT, "%.2f", value.third))
        }
        worst.forEachIndexed { index, value ->
            rows += listOf("ХУДШИЕ", (index + 1).toString(), value.first, value.second,
                String.format(java.util.Locale.ROOT, "%.2f", value.third))
        }
        return ReportResult("Три лучших и три худших согласующих", headers, rows,
            "Рассчитано по ${completed.size} завершённым этапам")
    }

    private fun vacationForecast(userId: Int?): ReportResult {
        val headers = listOf("Сотрудник", "Использовано в году", "Остаток", "Средний расход/мес.",
            "Месяцев до исчерпания", "Прогнозная дата", "Нижняя граница", "Верхняя граница", "Уровень доверия")
        if (userId == null) return ReportResult("Прогноз отпуска", headers, emptyList(), "Выберите сотрудника")
        val user = appUserRepository.findById(userId).orElse(null)
            ?: throw IllegalArgumentException("Сотрудник не найден")
        val now = LocalDate.now()
        val documents = documentRepository.findAllByAuthorIdOrderByCreatedAtDesc(userId)
        val used = documents.filter {
            it.periodEnd != null && it.periodEnd!!.year == now.year &&
                it.status.name in setOf("APPROVED", "SIGNED", "ARCHIVED")
        }.sumOf { it.vacationDays ?: 0 }.toDouble()
        val monthly = documents.filter {
            it.periodEnd != null && !it.periodEnd!!.isBefore(now.minusMonths(12)) &&
                it.status.name in setOf("APPROVED", "SIGNED", "ARCHIVED")
        }.map { (it.vacationDays ?: 0).toDouble() }.filter { it > 0 }
        val average = if (monthly.isEmpty()) 0.0 else monthly.average()
        val remaining = maxOf(28.0 - used, 0.0)
        val months = if (average > 0) remaining / average else null
        val forecast = months?.let { now.plusDays((it * 30).toLong()) }
        val sd = if (monthly.size >= 2) standardDeviation(monthly) else 0.0
        val margin = if (monthly.size >= 2) 1.96 * sd / sqrt(monthly.size.toDouble()) else 0.0
        val lowerMonths = if (average + margin > 0) remaining / (average + margin) else null
        val upperMonths = if (average - margin > 0) remaining / (average - margin) else months
        val lower = lowerMonths?.let { now.plusDays((it * 30).toLong()) }
        val upper = upperMonths?.let { now.plusDays((it * 30).toLong()) }
        val rows = listOf(listOf(
            user.fullName, used.toInt().toString(), remaining.toInt().toString(),
            String.format(java.util.Locale.ROOT, "%.2f", average),
            months?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "",
            forecast?.toString() ?: "", lower?.toString() ?: "", upper?.toString() ?: "", "95%"
        ))
        return ReportResult("Прогноз отпуска", headers, rows,
            "Расчёт для ${user.fullName}: остаток ${remaining.toInt()} дней")
    }

    private fun filteredDocuments(from: LocalDate?, to: LocalDate?): List<DocumentEntity> =
        documentRepository.findAllByOrderByCreatedAtDesc().filter { d ->
            val date = d.createdAt.toLocalDate()
            (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))
        }

    private fun filteredSteps(from: LocalDate?, to: LocalDate?): List<RouteStepEntity> =
        routeStepRepository.findAll().filter { s ->
            val date = s.decidedAt?.toLocalDate() ?: s.deadline?.toLocalDate() ?: s.document?.createdAt?.toLocalDate()
            date != null && (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))
        }

    private fun standardDeviation(values: List<Double>): Double {
        val average = values.average()
        val variance = values.sumOf { (it - average) * (it - average) } / (values.size - 1)
        return sqrt(variance)
    }

    private fun csvValue(value: String): String =
        if (value.contains(',') || value.contains(';') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value

    private fun workbookToBytes(workbook: Workbook): ByteArray =
        ByteArrayOutputStream().use { output ->
            workbook.write(output)
            output.toByteArray()
        }
}
