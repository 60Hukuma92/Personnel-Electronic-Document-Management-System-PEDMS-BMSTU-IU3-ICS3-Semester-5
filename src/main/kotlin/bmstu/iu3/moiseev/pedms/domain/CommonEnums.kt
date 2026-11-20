package bmstu.iu3.moiseev.pedms.domain

/**
 * Перечисления, связанные с учётными записями, уведомлениями и аудитом.
 *
 * Значения перечислений хранятся в базе в виде строк (EnumType.STRING)
 * и совпадают с CHECK-условиями из sql/01_schema.sql.
 */

/**
 * Роль пользователя (FR140).
 *
 * EMPLOYEE — сотрудник: составляет документы-основания, знакомится с ЛНА;
 * MANAGER  — руководитель: согласует и подписывает документы, делегирует этапы;
 * HR       — сотрудник отдела кадров: готовит приказы и листы ознакомления, ведёт архив;
 * ADMIN    — администратор: ведёт учётные записи, роли и правила маршрутизации.
 */
enum class UserRole(val title: String) {
    EMPLOYEE("Сотрудник"),
    MANAGER("Руководитель"),
    HR("Сотрудник ОК"),
    ADMIN("Администратор");

    /** Права администратора подразумевают права сотрудника отдела кадров. */
    val isAtLeastHr: Boolean
        get() = this == HR || this == ADMIN

    /** Права сотрудника отдела кадров подразумевают права руководителя. */
    val isAtLeastManager: Boolean
        get() = this == MANAGER || isAtLeastHr
}

/**
 * Статус учётной записи (FR020).
 *
 * CREATED — учётная запись создана, но ещё не активирована;
 * ACTIVE  — действует, пользователь может войти в систему;
 * BLOCKED — заблокирована, вход невозможен;
 * DELETED — удалена с обезличиванием персональных данных.
 */
enum class UserStatus(val title: String) {
    CREATED("Создан"),
    ACTIVE("Активен"),
    BLOCKED("Заблокирован"),
    DELETED("Удалён");

    val isActive: Boolean
        get() = this == ACTIVE
}

/**
 * Тип события, по которому создаётся уведомление (FR135).
 */
enum class NotificationEvent(val title: String) {
    DOCUMENT_ARRIVED("Документ поступил на согласование"),
    DOCUMENT_APPROVED("Документ согласован"),
    DOCUMENT_SIGNED("Документ подписан"),
    DOCUMENT_REJECTED("Документ отклонён"),
    DOCUMENT_RETURNED("Документ возвращён на доработку"),
    DEADLINE_NEAR("Приближается срок этапа"),
    DEADLINE_EXPIRED("Нарушен срок этапа"),
    ACQUAINTANCE_REQUIRED("Требуется ознакомление с ЛНА"),
    ACQUAINTANCE_REMINDER("Напоминание об ознакомлении"),
    ACQUAINTANCE_ESCALATED("Просрочка ознакомления"),
    COMMENT_ADDED("Добавлен комментарий"),
    DOCUMENT_ARCHIVED("Документ переведён в архив")
}

/** Канал доставки уведомления. */
enum class NotificationChannel(val title: String) {
    WEB("Веб-интерфейс"),
    EMAIL("Электронная почта"),
    SMS("SMS")
}

/**
 * Действие, зафиксированное в журнале аудита (FR080).
 */
enum class AuditAction(val title: String) {
    CREATED("Создание документа"),
    SUBMITTED("Отправка на согласование"),
    APPROVED("Согласование"),
    REJECTED("Отклонение"),
    RETURNED("Возврат на доработку"),
    SIGNED("Подписание"),
    DELEGATED("Делегирование этапа"),
    RECALLED("Отзыв документа"),
    DRAFT_SAVED("Сохранение черновика"),
    ROUTE_CHANGED("Изменение маршрута"),
    ORDER_CREATED("Формирование приказа"),
    ACKNOWLEDGED("Ознакомление с ЛНА"),
    SHEET_CREATED("Создание листа ознакомления"),
    ARCHIVED("Архивирование"),
    COMMENT("Комментарий"),
    IMPORTED("Импорт данных"),
    EXPORTED("Выгрузка данных"),
    USER_CREATED("Создание учётной записи"),
    USER_UPDATED("Изменение учётной записи"),
    USER_BLOCKED("Блокировка учётной записи"),
    USER_UNBLOCKED("Разблокировка учётной записи"),
    USER_DELETED("Удаление учётной записи")
}
