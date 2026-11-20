package bmstu.iu3.moiseev.pedms.dto

import java.time.LocalDate

data class DocumentForm(
    var typeId: Int? = null,
    var title: String = "",
    var periodStart: LocalDate? = null,
    var periodEnd: LocalDate? = null,
    var fields: MutableMap<String, String> = linkedMapOf()
)

class DocumentValidationException(
    val fieldErrors: Map<String, String>
) : RuntimeException(fieldErrors.values.joinToString("; "))
