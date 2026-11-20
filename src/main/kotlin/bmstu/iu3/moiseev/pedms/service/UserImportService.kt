package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.dto.ImportReport
import bmstu.iu3.moiseev.pedms.dto.ImportRowReport
import bmstu.iu3.moiseev.pedms.dto.UserForm
import bmstu.iu3.moiseev.pedms.dto.UserValidationException
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.repository.PositionRepository
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Загрузка учётных записей из файла XLSX или CSV (FR010).
 *
 * Файл проверяется построчно: строки, не прошедшие проверку, не импортируются,
 * а по каждой такой строке в отчёте указывается причина. Ошибка в одной строке
 * не прерывает загрузку остальных.
 *
 * Ожидаемые столбцы (наименования распознаются на русском и английском):
 *
 * | Столбец            | Обязательный | Пример                |
 * |--------------------|--------------|-----------------------|
 * | ФИО                | да           | Петрова Анна Сергеевна|
 * | Электронная почта  | да           | petrova@pedms.ru      |
 * | Табельный номер   | нет          | ИУ3-00001             |
 * | ИНН                | нет          | 500100732259          |
 * | СНИЛС              | нет          | 001-001-001 00        |
 * | Телефон            | нет          | +7 916 123-45-67      |
 * | Дата рождения     | нет          | 12.04.1990            |
 * | Подразделение      | да           | DEV                   |
 * | Должность         | да           | Инженер               |
 * | Роль               | нет          | EMPLOYEE              |
 * | Пароль             | нет          | pedms2026             |
 */
@Service
class UserImportService(
    private val userManagementService: UserManagementService,
    private val departmentRepository: DepartmentRepository,
    private val positionRepository: PositionRepository,
    transactionManager: PlatformTransactionManager
) {

    private val log = LoggerFactory.getLogger(UserImportService::class.java)

    /** Каждая строка импортируется в отдельной транзакции: ошибка одной строки
     *  не откатывает уже загруженные строки. */
    private val rowTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /**
     * Загрузка учётных записей из файла.
     *
     * @throws IllegalArgumentException если файл пуст или в нём нет обязательных столбцов.
     */
    fun import(file: MultipartFile): ImportReport {
        if (file.isEmpty) {
            throw IllegalArgumentException("Файл не выбран или пуст")
        }

        val rows = readRows(file)
        if (rows.size < 2) {
            throw IllegalArgumentException("В файле нет строк с данными")
        }

        val header = rows.first()
        val mapping = mapColumns(header)
        if (mapping.fullName == null || mapping.email == null) {
            throw IllegalArgumentException(
                "В файле отсутствуют обязательные столбцы «ФИО» и «Электронная почта»"
            )
        }

        val departments = departmentRepository.findAll().associateBy { it.code.lowercase() }
        val departmentsByName = departmentRepository.findAll().associateBy { it.name.lowercase() }
        val positions = positionRepository.findAllByOrderByNameAsc().associateBy { it.name.lowercase() }

        val reports = mutableListOf<ImportRowReport>()
        var imported = 0

        rows.drop(1).forEachIndexed { index, row ->
            val rowNumber = index + 2
            if (row.all { it.isBlank() }) {
                return@forEachIndexed
            }

            val fullName = cell(row, mapping.fullName)
            val email = cell(row, mapping.email)

            try {
                val form = buildForm(row, mapping, departments, departmentsByName, positions)
                val created = rowTransaction.execute { userManagementService.createUser(form) }
                    ?: throw IllegalStateException("Не удалось сохранить учётную запись")
                imported++
                reports.add(
                    ImportRowReport(
                        rowNumber = rowNumber,
                        fullName = created.user.fullName,
                        email = created.user.email,
                        password = created.password
                    )
                )
            } catch (ex: UserValidationException) {
                reports.add(
                    ImportRowReport(rowNumber, fullName, email, ex.fieldErrors.values.joinToString("; "))
                )
            } catch (ex: IllegalArgumentException) {
                reports.add(ImportRowReport(rowNumber, fullName, email, ex.message))
            } catch (ex: Exception) {
                log.warn("Строка {} не импортирована: {}", rowNumber, ex.message)
                reports.add(
                    ImportRowReport(rowNumber, fullName, email, ex.message ?: "Ошибка загрузки строки")
                )
            }
        }

        log.info("Импорт учётных записей: обработано строк — {}, загружено — {}", reports.size, imported)
        return ImportReport(totalRows = reports.size, importedRows = imported, rows = reports)
    }

    /**
     * Шаблон файла для загрузки — пример с заголовками и одной строкой данных.
     */
    fun templateCsv(): ByteArray {
        val header = "ФИО;Электронная почта;Табельный номер;ИНН;СНИЛС;Телефон;Дата рождения;Подразделение;Должность;Роль;Пароль"
        val example = "Петрова Анна Сергеевна;petrova@pedms.ru;ИУ3-00001;500100732259;001-001-001 00;" +
            "+7 916 123-45-67;12.04.1990;DEV;Инженер;EMPLOYEE;pedms2026"
        // BOM — чтобы Excel корректно распознал кодировку UTF-8
        return ("\uFEFF" + header + "\r\n" + example + "\r\n").toByteArray(StandardCharsets.UTF_8)
    }

    // ------------------------------------------------------------------
    // Разбор файла
    // ------------------------------------------------------------------

    private fun readRows(file: MultipartFile): List<List<String>> {
        val filename = file.originalFilename?.lowercase(Locale.ROOT).orEmpty()
        val isExcel = filename.endsWith(".xlsx") || filename.endsWith(".xls")
        return if (isExcel) {
            file.inputStream.use { readExcel(it) }
        } else {
            val text = String(file.bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
            readCsv(text)
        }
    }

    private fun readExcel(input: InputStream): List<List<String>> {
        WorkbookFactory.create(input).use { workbook: Workbook ->
            val sheet: Sheet = workbook.getSheetAt(0)
            val formatter = DataFormatter(Locale.forLanguageTag("ru"))
            val rows = mutableListOf<List<String>>()

            sheet.forEach { row: Row ->
                val values = (0 until row.lastCellNum).map { column ->
                    val cell = row.getCell(column)
                    readCell(cell, formatter)
                }
                if (values.any { it.isNotBlank() }) {
                    rows.add(values)
                }
            }
            return rows
        }
    }

    private fun readCell(cell: Cell?, formatter: DataFormatter): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.NUMERIC ->
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDate.parse(cell.dateCellValue.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString())
                        .format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
                } else {
                    formatter.formatCellValue(cell)
                }
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> formatter.formatCellValue(cell)
            else -> ""
        }
    }

    /**
     * Разбор CSV с поддержкой кавычек и разделителя «;» или «,».
     */
    private fun readCsv(text: String): List<List<String>> {
        val delimiter = detectDelimiter(text)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < text.length) {
            val char = text[index]
            when {
                inQuotes && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                !inQuotes && char == delimiter -> {
                    row.add(field.toString().trim())
                    field.setLength(0)
                }
                !inQuotes && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row.add(field.toString().trim())
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }

        row.add(field.toString().trim())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    private fun detectDelimiter(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val semicolons = firstLine.count { it == ';' }
        val commas = firstLine.count { it == ',' }
        return if (semicolons >= commas) ';' else ','
    }

    // ------------------------------------------------------------------
    // Сопоставление столбцов и строк
    // ------------------------------------------------------------------

    /** Соответствие столбцов файла полям формы. */
    private data class ColumnMapping(
        val fullName: Int? = null,
        val email: Int? = null,
        val personnelNumber: Int? = null,
        val inn: Int? = null,
        val snils: Int? = null,
        val phone: Int? = null,
        val dateOfBirth: Int? = null,
        val department: Int? = null,
        val position: Int? = null,
        val role: Int? = null,
        val password: Int? = null
    )

    private fun mapColumns(header: List<String>): ColumnMapping {
        val normalized = header.map { normalizeHeader(it) }
        fun indexOf(vararg aliases: String): Int? {
            aliases.forEach { alias ->
                val position = normalized.indexOfFirst { it == alias }
                if (position >= 0) return position
            }
            return null
        }

        return ColumnMapping(
            fullName = indexOf("фио", "фмио", "фамилияимяотчество", "фамилия", "имя", "полноеимя", "fullname", "name"),
            email = indexOf(
                "электроннаяпочта", "email", "почта", "логин", "адресэлектроннойпочты",
                "электронныйадрес", "mail"
            ),
            personnelNumber = indexOf(
                "персональныйномер", "табельныйномер", "табельный", "personnelnumber", "tabelnumber"
            ),
            inn = indexOf("инн", "inn"),
            snils = indexOf("снилс", "snils"),
            phone = indexOf("телефон", "номертелефона", "мобильныйтелефон", "phone", "phonenumber"),
            dateOfBirth = indexOf("датарождения", "дата рождения", "birthdate", "dateofbirth", "др"),
            department = indexOf("подразделение", "отдел", "кодподразделения", "department", "departmentcode"),
            position = indexOf("должность", "position", "наименованиедолжности"),
            role = indexOf("роль", "role"),
            password = indexOf("пароль", "password")
        )
    }

    /** Приведение наименования столбца к ключу сравнения: без пробелов и знаков. */
    private fun normalizeHeader(value: String): String =
        value.trim().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun cell(row: List<String>, index: Int?): String =
        index?.let { row.getOrNull(it)?.trim().orEmpty() }.orEmpty()

    private fun buildForm(
        row: List<String>,
        mapping: ColumnMapping,
        departments: Map<String, bmstu.iu3.moiseev.pedms.entity.DepartmentEntity>,
        departmentsByName: Map<String, bmstu.iu3.moiseev.pedms.entity.DepartmentEntity>,
        positions: Map<String, bmstu.iu3.moiseev.pedms.entity.PositionEntity>
    ): UserForm {
        val departmentText = cell(row, mapping.department)
        val department = departments[departmentText.lowercase(Locale.ROOT)]
            ?: departmentsByName[departmentText.lowercase(Locale.ROOT)]

        val positionText = cell(row, mapping.position)
        val position = positions[positionText.lowercase(Locale.ROOT)]

        return UserForm(
            fullName = cell(row, mapping.fullName),
            email = cell(row, mapping.email),
            personnelNumber = cell(row, mapping.personnelNumber),
            inn = cell(row, mapping.inn),
            snils = cell(row, mapping.snils),
            phone = cell(row, mapping.phone),
            dateOfBirth = cell(row, mapping.dateOfBirth),
            departmentId = department?.id?.toString().orEmpty(),
            positionId = position?.id?.toString().orEmpty(),
            role = parseRole(cell(row, mapping.role)),
            status = "ACTIVE",
            password = cell(row, mapping.password)
        )
    }

    /** Распознавание роли по коду или русскому наименованию. */
    private fun parseRole(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) return UserRole.EMPLOYEE.name
        UserRole.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }?.let { return it.name }
        return when (text.lowercase(Locale.ROOT)) {
            "сотрудник" -> UserRole.EMPLOYEE.name
            "руководитель" -> UserRole.MANAGER.name
            "сотрудник ок", "ок", "кадры", "отдел кадров" -> UserRole.HR.name
            "администратор", "админ" -> UserRole.ADMIN.name
            else -> text.uppercase(Locale.ROOT)
        }
    }
}
