package bmstu.iu3.moiseev.pedms.domain

/**
 * Перечисления, связанные с документами, маршрутом согласования
 * и листами ознакомления.
 *
 * Значения перечислений хранятся в базе в виде строк (EnumType.STRING)
 * и совпадают с CHECK-условиями из sql/01_schema.sql.
 */

/**
 * Статус кадрового документа (FR200).
 *
 * DRAFT       — черновик, правит только автор;
 * IN_APPROVAL — документ на согласовании;
 * REWORK      — документ возвращён на доработку;
 * APPROVED    — документ согласован, ожидает подписания или решения по приказу;
 * SIGNED      — документ подписан, не изменяется и не удаляется (TR040);
 * REJECTED    — документ отклонён;
 * RECALLED    — документ отозван инициатором;
 * ARCHIVED    — документ переведён в архив.
 */
enum class DocStatus(val title: String) {
    DRAFT("Черновик"),
    IN_APPROVAL("На согласовании"),
    REWORK("На доработке"),
    APPROVED("Согласован"),
    SIGNED("Подписан"),
    REJECTED("Отклонён"),
    RECALLED("Отозван"),
    ARCHIVED("В архиве");

    /** Документ в архиве или подписанный документ изменению не подлежит. */
    val isImmutable: Boolean
        get() = this == SIGNED || this == ARCHIVED

    /** Открыт для работы: документ можно редактировать или отправлять на согласование. */
    val isEditable: Boolean
        get() = this == DRAFT || this == REWORK
}

/**
 * Категория вида документа.
 */
enum class DocCategory(val title: String) {
    APPLICATION("Заявление"),
    ORDER("Приказ"),
    LNA("Локальный нормативный акт"),
    MEMO("Служебная записка"),
    OTHER("Прочий документ");

    /** Префикс регистрационного номера документа этой категории. */
    val numberPrefix: String
        get() = when (this) {
            APPLICATION -> "З"
            ORDER -> "П"
            LNA -> "ЛНА"
            MEMO -> "СЛ"
            OTHER -> "Д"
        }
}

/**
 * Действие участника маршрута на этапе согласования (FR070, FR210).
 */
enum class StepAction(val title: String) {
    APPROVE("Согласование"),
    SIGN("Подписание"),
    REVIEW("Рассмотрение")
}

/**
 * Статус этапа маршрута согласования (FR210).
 */
enum class StepStatus(val title: String) {
    PENDING("Ожидает решения"),
    IN_PROGRESS("В работе"),
    APPROVED("Согласован"),
    REJECTED("Отклонён"),
    RETURNED("Возвращён на доработку"),
    DELEGATED("Делегирован"),
    SKIPPED("Пропущен");

    /** Этап ещё не обработан — документ находится в работе. */
    val isPending: Boolean
        get() = this == PENDING || this == IN_PROGRESS
}

/**
 * Аудитория листа ознакомления (FR110).
 */
enum class AudienceType(val title: String) {
    ALL("Вся организация"),
    DEPARTMENT("Подразделение"),
    POSITION("Должность"),
    LIST("Перечень сотрудников")
}

/**
 * Статус листа ознакомления.
 */
enum class SheetStatus(val title: String) {
    ACTIVE("Действует"),
    COMPLETED("Завершён"),
    CANCELLED("Отменён")
}

/**
 * Статус отметки об ознакомлении (FR115).
 */
enum class MarkStatus(val title: String) {
    PENDING("Не ознакомлен"),
    ACKNOWLEDGED("Ознакомлен")
}
