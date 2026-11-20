package bmstu.iu3.moiseev.pedms.dto

data class OrderImportRowReport(
    val rowNumber: Int,
    val applicationId: Int?,
    val applicationTitle: String,
    val documentId: Int?,
    val error: String? = null
) {
    val isSuccess: Boolean
        get() = error == null
}

data class OrderImportReport(
    val totalRows: Int = 0,
    val createdRows: Int = 0,
    val rows: List<OrderImportRowReport> = emptyList()
) {
    val failedRows: Int
        get() = rows.count { !it.isSuccess }
}
