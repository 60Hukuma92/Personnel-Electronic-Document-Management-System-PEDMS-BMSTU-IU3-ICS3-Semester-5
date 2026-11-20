package bmstu.iu3.moiseev.pedms.dto

/**
 * Результат проверки одной строки файла при загрузке учётных записей (FR010).
 *
 * Если [error] задан, строка не импортирована, а текст ошибки показывается
 * администратору в отчёте.
 */
data class ImportRowReport(
    /** Номер строки в файле (первая строка с данными — 2). */
    val rowNumber: Int,

    /** ФИО из строки файла. */
    val fullName: String,

    /** Адрес электронной почты из строки файла. */
    val email: String,

    /** Текст ошибки; null — строка импортирована. */
    val error: String? = null,

    /**
     * Временный пароль, сформированный системой для импортированной записи.
     *
     * Показывается администратору один раз — после смены пароля пользователем
     * он больше не доступен.
     */
    val password: String? = null
) {
    val isSuccess: Boolean
        get() = error == null
}

/**
 * Отчёт о загрузке учётных записей из файла (FR010).
 */
data class ImportReport(
    /** Всего строк данных в файле. */
    val totalRows: Int = 0,

    /** Строк, прошедших проверку и импортированных. */
    val importedRows: Int = 0,

    /** Построчный результат проверки. */
    val rows: List<ImportRowReport> = emptyList()
) {
    /** Число строк с ошибками. */
    val failedRows: Int
        get() = rows.count { !it.isSuccess }
}
