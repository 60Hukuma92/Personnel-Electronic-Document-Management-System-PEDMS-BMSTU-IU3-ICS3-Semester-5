package bmstu.iu3.moiseev.pedms.dto

/**
 * Форма создания и редактирования учётной записи (FR010, FR190).
 *
 * Значения полей приходят из формы в виде строк и приводятся к типам
 * базы данных в [bmstu.iu3.moiseev.pedms.service.UserManagementService],
 * где выполняется проверка по правилам доменов d_email, d_inn, d_snils,
 * d_personnel_number и d_full_name.
 */
data class UserForm(
    /** Фамилия, имя, отчество. */
    var fullName: String = "",

    /** Адрес электронной почты — используется как логин. */
    var email: String = "",

    /** Табельный номер. */
    var personnelNumber: String = "",

    /** ИНН — 12 цифр. */
    var inn: String = "",

    /** СНИЛС — 11 цифр. */
    var snils: String = "",

    /** Телефон в формате +7 XXX XXX-XX-XX. */
    var phone: String = "",

    /** Дата рождения в формате ДД.ММ.ГГГГ. */
    var dateOfBirth: String = "",

    /** Идентификатор подразделения (справочник department). */
    var departmentId: String = "",

    /** Идентификатор должности (справочник position). */
    var positionId: String = "",

    /** Роль: EMPLOYEE, MANAGER, HR, ADMIN. */
    var role: String = "EMPLOYEE",

    /** Статус учётной записи: CREATED, ACTIVE, BLOCKED, DELETED. */
    var status: String = "CREATED",

    /**
     * Пароль. Если поле оставлено пустым, система формирует временный
     * пароль и показывает его администратору один раз (FR020).
     */
    var password: String = ""
)

/**
 * Ошибка проверки данных формы.
 *
 * Ключ карты — имя поля формы, значение — текст ошибки для пользователя.
 */
class UserValidationException(val fieldErrors: Map<String, String>) :
    RuntimeException(fieldErrors.values.firstOrNull() ?: "Проверка данных не пройдена")
