package bmstu.iu3.moiseev.pedms.dto

import bmstu.iu3.moiseev.pedms.domain.AudienceType
import java.time.LocalDate

data class AcquaintanceSheetForm(
    var documentId: Int? = null,
    var audienceType: AudienceType = AudienceType.ALL,
    var audienceRef: String? = null,
    var deadline: LocalDate? = null,
    var employeeIds: String? = null
) {
    fun reference(): String? =
        if (audienceType == AudienceType.LIST) employeeIds?.trim() else audienceRef?.trim()
}

data class AcquaintancePreview(
    val users: List<bmstu.iu3.moiseev.pedms.entity.AppUserEntity>,
    val total: Int
)
