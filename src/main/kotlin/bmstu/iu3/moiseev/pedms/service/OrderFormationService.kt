package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.AuditAction
import bmstu.iu3.moiseev.pedms.domain.DocCategory
import bmstu.iu3.moiseev.pedms.domain.DocStatus
import bmstu.iu3.moiseev.pedms.dto.OrderImportReport
import bmstu.iu3.moiseev.pedms.dto.OrderImportRowReport
import bmstu.iu3.moiseev.pedms.entity.AuditLogEntity
import bmstu.iu3.moiseev.pedms.entity.DocumentEntity
import bmstu.iu3.moiseev.pedms.repository.AuditLogRepository
import bmstu.iu3.moiseev.pedms.repository.DocTypeRepository
import bmstu.iu3.moiseev.pedms.repository.DocumentRepository
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

@Service
class OrderFormationService(
    private val documentRepository: DocumentRepository,
    private val docTypeRepository: DocTypeRepository,
    private val auditLogRepository: AuditLogRepository,
    private val currentUserService: CurrentUserService,
    private val routingService: RoutingService,
    transactionManager: PlatformTransactionManager
) {
    private val log = LoggerFactory.getLogger(OrderFormationService::class.java)
    private val rowTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun availableApplications(): List<DocumentEntity> =
        documentRepository.findApprovedApplicationsWithoutOrder()

    @org.springframework.transaction.annotation.Transactional
    fun createFromApplication(applicationId: Int): DocumentEntity {
        val application = findAvailableApplication(applicationId)
        val orderType = docTypeRepository.findAllByIsActiveTrueOrderByNameAsc()
            .firstOrNull { it.category == DocCategory.ORDER && it.code == "PRIKAZ_OTP" }
            ?: docTypeRepository.findAllByIsActiveTrueOrderByNameAsc()
                .firstOrNull { it.category == DocCategory.ORDER }
            ?: throw IllegalStateException("В справочнике нет активного вида приказа")

        val content = buildOrderContent(application)
        val order = documentRepository.save(
            DocumentEntity(
                type = orderType,
                author = currentUserService.requireCurrentUser(),
                baseDoc = application,
                title = "Приказ на основании заявления № ${application.number ?: application.id}",
                status = DocStatus.DRAFT,
                contentJson = content,
                periodStart = application.periodStart,
                periodEnd = application.periodEnd,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now()
            )
        )
        auditLogRepository.save(
            AuditLogEntity(
                document = order,
                user = currentUserService.requireCurrentUser(),
                action = AuditAction.ORDER_CREATED,
                comment = "Сформирован из заявления № ${application.number ?: application.id}",
                changedFields = mapOf("baseDocId" to application.id)
            )
        )
        log.info("Сформирован приказ {} из заявления {}", order.id, application.id)
        return order
    }

    fun importOrders(file: MultipartFile, submitToRoute: Boolean = false): OrderImportReport {
        if (file.isEmpty) throw IllegalArgumentException("Файл не выбран или пуст")
        val rows = readRows(file)
        if (rows.size < 2) throw IllegalArgumentException("В файле нет строк с данными")

        val header = rows.first()
        val mapping = mapColumns(header)
        if (mapping.applicationId == null) {
            throw IllegalArgumentException(
                "В файле отсутствует обязательный столбец «ID заявления»"
            )
        }

        val reports = mutableListOf<OrderImportRowReport>()
        var created = 0
        rows.drop(1).forEachIndexed { index, row ->
            val rowNumber = index + 2
            if (row.all { it.isBlank() }) return@forEachIndexed
            val idText = cell(row, mapping.applicationId)
            val applicationId = idText.toIntOrNull()
            val applicationTitle = if (applicationId != null) {
                documentRepository.findById(applicationId).orElse(null)?.title ?: ""
            } else ""

            try {
                val result = rowTransaction.execute {
                    val resultApplicationId = applicationId
                        ?: throw IllegalArgumentException("Укажите корректный числовой ID заявления")
                    val order = createFromApplication(resultApplicationId)
                    if (submitToRoute) routingService.submit(order.id ?: throw IllegalStateException("Приказ не сохранён"))
                    order
                } ?: throw IllegalStateException("Не удалось сохранить приказ")
                created++
                reports.add(
                    OrderImportRowReport(
                        rowNumber = rowNumber,
                        applicationId = applicationId,
                        applicationTitle = applicationTitle,
                        documentId = result.id
                    )
                )
            } catch (ex: IllegalArgumentException) {
                reports.add(OrderImportRowReport(rowNumber, applicationId, applicationTitle, null, ex.message))
            } catch (ex: IllegalStateException) {
                reports.add(OrderImportRowReport(rowNumber, applicationId, applicationTitle, null, ex.message))
            } catch (ex: Exception) {
                log.warn("Строка {} не обработана: {}", rowNumber, ex.message)
                reports.add(
                    OrderImportRowReport(
                        rowNumber,
                        applicationId,
                        applicationTitle,
                        null,
                        ex.message ?: "Ошибка формирования приказа"
                    )
                )
            }
        }
        log.info("Пакетное формирование приказов: обработано {}, создано {}, ошибок {}",
            reports.size, created, reports.count { !it.isSuccess })
        return OrderImportReport(totalRows = reports.size, createdRows = created, rows = reports)
    }

    @org.springframework.transaction.annotation.Transactional
    fun submitBatch(documentIds: List<Int>): List<DocumentEntity> =
        documentIds.distinct().map { id ->
            routingService.submit(id)
        }

    fun templateCsv(): ByteArray =
        ("\uFEFFID заявления\r\n").toByteArray(StandardCharsets.UTF_8)

    private fun findAvailableApplication(applicationId: Int): DocumentEntity {
        val application = documentRepository.findById(applicationId).orElse(null)
            ?: throw IllegalArgumentException("Заявление не найдено")
        if (application.type?.category != DocCategory.APPLICATION) {
            throw IllegalArgumentException("Документ не является заявлением")
        }
        if (application.status != DocStatus.APPROVED && application.status != DocStatus.SIGNED) {
            throw IllegalArgumentException("Приказ можно сформировать только из согласованного заявления")
        }
        if (application.baseDoc != null) {
            throw IllegalArgumentException("Для заявления уже сформирован приказ")
        }
        val existing = documentRepository.findAllByStatusOrderByCreatedAtDesc(DocStatus.DRAFT)
            .any { it.baseDoc?.id == application.id }
        val existingNonDraft = documentRepository.findAccessibleTo(
            currentUserService.requireCurrentUser().id ?: 0
        ).any { it.baseDoc?.id == application.id }
        if (existing || existingNonDraft) {
            throw IllegalArgumentException("Для заявления уже сформирован приказ")
        }
        return application
    }

    private fun buildOrderContent(application: DocumentEntity): Map<String, Any?> {
        val content = linkedMapOf<String, Any?>()
        content["order_basis"] = "Заявление № ${application.number ?: application.id}"
        val start = application.periodStart?.toString()
            ?: application.contentJson["start_date"]?.toString()
        val end = application.periodEnd?.toString()
            ?: application.contentJson["end_date"]?.toString()
        content["order_period"] = if (!start.isNullOrBlank() && !end.isNullOrBlank()) {
            "$start — $end"
        } else {
            "Период из заявления № ${application.number ?: application.id}"
        }
        content["order_reason"] = application.contentJson["comment"]?.toString()?.takeIf { it.isNotBlank() }
        content["employee_full_name"] = application.author?.fullName
        content["department"] = application.author?.department?.name
        content["position"] = application.author?.position?.name
        content["personnel_number"] = application.author?.personnelNumber
        content["application_id"] = application.id
        content["application_number"] = application.number
        return content
    }

    private data class ColumnMapping(val applicationId: Int? = null)

    private fun mapColumns(header: List<String>): ColumnMapping {
        val normalized = header.mapIndexed { index, value -> normalizeHeader(value) to index }.toMap()
        fun find(names: Set<String>): Int? = names.firstNotNullOfOrNull { normalized[it] }
        return ColumnMapping(
            applicationId = find(setOf(
                "id заявления", "ид заявления", "application id", "applicationid",
                "id приложения", "основание id", "id основания"
            ))
        )
    }

    private fun normalizeHeader(value: String): String =
        value.lowercase(Locale.forLanguageTag("ru"))
            .replace("_", " ")
            .replace("-", " ")
            .trim()

    private fun readRows(file: MultipartFile): List<List<String>> {
        val name = file.originalFilename?.lowercase(Locale.ROOT).orEmpty()
        return if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            file.inputStream.use { input ->
                WorkbookFactory.create(input).use { workbook ->
                    val sheet = workbook.getSheetAt(0)
                    val formatter = DataFormatter(Locale.forLanguageTag("ru"))
                    val rows = mutableListOf<List<String>>()
                    sheet.forEach { row ->
                        val values = (0 until row.lastCellNum).map { column ->
                            val cell = row.getCell(column)
                            if (cell == null) "" else formatter.formatCellValue(cell).trim()
                        }
                        if (values.any { it.isNotBlank() }) rows.add(values)
                    }
                    rows
                }
            }
        } else {
            readCsv(String(file.bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF"))
        }
    }

    private fun readCsv(text: String): List<List<String>> {
        val delimiter = if (text.lineSequence().firstOrNull().orEmpty().count { it == ';' } >=
            text.lineSequence().firstOrNull().orEmpty().count { it == ',' }) ';' else ','
        val result = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == delimiter -> {
                    row.add(field.toString().trim()); field.setLength(0)
                }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString().trim())
                    if (row.any { it.isNotBlank() }) result.add(row)
                    row = mutableListOf(); field.setLength(0)
                }
                else -> field.append(ch)
            }
            i++
        }
        row.add(field.toString().trim())
        if (row.any { it.isNotBlank() }) result.add(row)
        return result
    }

    private fun cell(row: List<String>, index: Int?): String =
        index?.let { row.getOrNull(it) }?.trim().orEmpty()
}
