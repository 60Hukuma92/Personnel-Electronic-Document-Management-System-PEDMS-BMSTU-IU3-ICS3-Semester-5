package bmstu.iu3.moiseev.pedms.service

import bmstu.iu3.moiseev.pedms.domain.AuditAction
import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.domain.UserStatus
import bmstu.iu3.moiseev.pedms.dto.UserForm
import bmstu.iu3.moiseev.pedms.dto.UserValidationException
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.AuditLogEntity
import bmstu.iu3.moiseev.pedms.entity.DepartmentEntity
import bmstu.iu3.moiseev.pedms.entity.PositionEntity
import bmstu.iu3.moiseev.pedms.entity.UserSettingsEntity
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.AuditLogRepository
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.repository.PositionRepository
import bmstu.iu3.moiseev.pedms.repository.UserSettingsRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.Predicate
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Управление учётными записями пользователей (UC040, FR010, FR020, FR190).
 *
 * Сервис реализует жизненный цикл учётной записи:
 *   • создание — по одной или загрузкой файла (FR010);
 *   • активация, блокировка и разблокировка (FR020);
 *   • удаление с обезличиванием персональных данных (FR020);
 *   • изменение сведений о пользователе (FR190).
 *
 * Проверка данных выполняется по тем же правилам, что и домены базы данных
 * (d_email, d_inn, d_snils, d_personnel_number, d_full_name) и CHECK-условиям
 * app_users, — чтобы пользователь получал понятное сообщение сразу, а не
 * сообщение СУБД.
 */
@Service
class UserManagementService(
    private val userRepository: AppUserRepository,
    private val departmentRepository: DepartmentRepository,
    private val positionRepository: PositionRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val auditLogRepository: AuditLogRepository,
    private val passwordEncoder: PasswordEncoder,
    private val currentUserService: CurrentUserService,
    private val entityManager: EntityManager
) {

    private val log = LoggerFactory.getLogger(UserManagementService::class.java)
    private val random = SecureRandom()

    // ------------------------------------------------------------------
    // Выборка и поиск
    // ------------------------------------------------------------------

    /**
     * Реестр учётных записей с поиском и фильтрами (UC040).
     *
     * Поиск выполняется по ФИО, адресу электронной почты, табельному номеру
     * и ИНН; фильтры — по роли, статусу и подразделению.
     */
    @Transactional(readOnly = true)
    fun findUsers(
        query: String? = null,
        role: UserRole? = null,
        status: UserStatus? = null,
        departmentId: Int? = null
    ): List<AppUserEntity> {
        val criteria = entityManager.criteriaBuilder
        val queryBuilder = criteria.createQuery(AppUserEntity::class.java)
        val root = queryBuilder.from(AppUserEntity::class.java)
        val predicates = mutableListOf<Predicate>()

        if (!query.isNullOrBlank()) {
            val pattern = "%" + query.trim().lowercase(Locale.ROOT) + "%"
            // f_lower_ru — приведение к нижнему регистру с учётом кириллицы
            // независимо от локали сервера базы данных
            fun lowerCase(path: jakarta.persistence.criteria.Expression<String>) =
                criteria.function("f_lower_ru", String::class.java, path)

            predicates.add(
                criteria.or(
                    criteria.like(lowerCase(root.get<String>("fullName")), pattern),
                    criteria.like(lowerCase(root.get<String>("email")), pattern),
                    criteria.like(lowerCase(criteria.coalesce(root.get<String>("personnelNumber"), "")), pattern),
                    criteria.like(lowerCase(criteria.coalesce(root.get<String>("inn"), "")), pattern)
                )
            )
        }
        role?.let { predicates.add(criteria.equal(root.get<UserRole>("role"), it)) }
        status?.let { predicates.add(criteria.equal(root.get<UserStatus>("status"), it)) }
        departmentId?.let {
            predicates.add(criteria.equal(root.get<Any>("department").get<Int>("id"), it))
        }

        queryBuilder.where(*predicates.toTypedArray())
        queryBuilder.orderBy(criteria.asc(root.get<String>("fullName")))
        return entityManager.createQuery(queryBuilder).resultList
    }

    /** Учётная запись по идентификатору. */
    @Transactional(readOnly = true)
    fun findById(id: Int): AppUserEntity? = userRepository.findById(id).orElse(null)

    /** Количество учётных записей в разрезе статусов — для панели фильтров. */
    @Transactional(readOnly = true)
    fun countByStatus(): Map<UserStatus, Long> =
        UserStatus.entries.associateWith { userRepository.countByStatus(it) }

    /** Количество учётных записей в разрезе ролей. */
    @Transactional(readOnly = true)
    fun countByRole(): Map<UserRole, Long> =
        UserRole.entries.associateWith { userRepository.countByRole(it) }

    // ------------------------------------------------------------------
    // Создание и изменение (FR010, FR190)
    // ------------------------------------------------------------------

    /**
     * Создание учётной записи (FR010).
     *
     * @return созданная учётная запись и временный пароль, если он был сформирован системой.
     */
    @Transactional
    fun createUser(form: UserForm): CreatedUser {
        val errors = validate(form, userId = null)
        if (errors.isNotEmpty()) {
            throw UserValidationException(errors)
        }

        val password = form.password.trim().ifEmpty { generatePassword() }
        val user = AppUserEntity(
            department = findDepartment(form.departmentId),
            position = findPosition(form.positionId),
            personnelNumber = form.personnelNumber.trim().ifEmpty { null },
            fullName = form.fullName.trim(),
            dateOfBirth = parseDate(form.dateOfBirth),
            inn = form.inn.trim().ifEmpty { null },
            snils = form.snils.trim().ifEmpty { null },
            phone = normalizePhone(form.phone.trim()) ?: form.phone.trim().ifEmpty { null },
            email = form.email.trim().lowercase(Locale.ROOT),
            role = parseRole(form.role),
            status = parseStatus(form.status),
            passwordHash = passwordEncoder.encode(password)
        )

        val saved = userRepository.save(user)
        userSettingsRepository.save(UserSettingsEntity(userId = saved.id))

        audit(
            AuditAction.USER_CREATED,
            "Учётная запись «${saved.fullName}» создана. Роль: ${saved.role.title}",
            mapOf(
                "email" to saved.email,
                "role" to saved.role.name,
                "status" to saved.status.name,
                "department" to (saved.department?.name ?: "—"),
                "position" to (saved.position?.name ?: "—")
            )
        )
        log.info("Создана учётная запись {} ({})", saved.email, saved.role)
        return CreatedUser(saved, password)
    }

    /**
     * Изменение сведений об учётной записи (FR190).
     *
     * Статус DELETED через эту операцию не устанавливается: удаление
     * выполняется только действием [deleteUser] с обезличиванием данных.
     */
    @Transactional
    fun updateUser(id: Int, form: UserForm): AppUserEntity {
        val user = findById(id) ?: throw IllegalArgumentException("Учётная запись не найдена")
        val errors = validate(form, userId = id)
        if (errors.isNotEmpty()) {
            throw UserValidationException(errors)
        }

        val before = mapOf(
            "email" to user.email,
            "role" to user.role.name,
            "status" to user.status.name,
            "department" to (user.department?.name ?: "—"),
            "position" to (user.position?.name ?: "—")
        )

        user.department = findDepartment(form.departmentId)
        user.position = findPosition(form.positionId)
        user.personnelNumber = form.personnelNumber.trim().ifEmpty { null }
        user.fullName = form.fullName.trim()
        user.dateOfBirth = parseDate(form.dateOfBirth)
        user.inn = form.inn.trim().ifEmpty { null }
        user.snils = form.snils.trim().ifEmpty { null }
        user.phone = normalizePhone(form.phone.trim()) ?: form.phone.trim().ifEmpty { null }
        user.email = form.email.trim().lowercase(Locale.ROOT)
        user.role = parseRole(form.role)

        val newStatus = parseStatus(form.status)
        if (newStatus != UserStatus.DELETED) {
            user.status = newStatus
        }

        val saved = userRepository.save(user)

        val after = mapOf(
            "email" to saved.email,
            "role" to saved.role.name,
            "status" to saved.status.name,
            "department" to (saved.department?.name ?: "—"),
            "position" to (saved.position?.name ?: "—")
        )

        audit(
            AuditAction.USER_UPDATED,
            "Сведения об учётной записи «${saved.fullName}» изменены",
            mapOf("до" to before, "после" to after)
        )
        return saved
    }

    // ------------------------------------------------------------------
    // Жизненный цикл учётной записи (FR020)
    // ------------------------------------------------------------------

    /**
     * Активация, блокировка или разблокировка учётной записи (FR020).
     */
    @Transactional
    fun changeStatus(id: Int, status: UserStatus): AppUserEntity {
        val user = findById(id) ?: throw IllegalArgumentException("Учётная запись не найдена")
        val previous = user.status
        user.status = status
        val saved = userRepository.save(user)

        val action = when (status) {
            UserStatus.ACTIVE -> AuditAction.USER_UPDATED
            UserStatus.BLOCKED -> AuditAction.USER_BLOCKED
            UserStatus.CREATED -> AuditAction.USER_UPDATED
            UserStatus.DELETED -> AuditAction.USER_DELETED
        }
        val comment = when (status) {
            UserStatus.ACTIVE -> "Учётная запись «${saved.fullName}» активирована"
            UserStatus.BLOCKED -> "Учётная запись «${saved.fullName}» заблокирована"
            UserStatus.CREATED -> "Учётная запись «${saved.fullName}» переведена в состояние «Создан»"
            UserStatus.DELETED -> "Учётная запись «${saved.fullName}» удалена"
        }
        audit(action, comment, mapOf("статус" to "${previous.title} → ${status.title}"))
        log.info("Учётная запись {}: статус {} → {}", saved.email, previous, status)
        return saved
    }

    /** Блокировка учётной записи — вход в систему становится невозможным. */
    fun block(id: Int): AppUserEntity = changeStatus(id, UserStatus.BLOCKED)

    /** Разблокировка учётной записи. */
    fun unblock(id: Int): AppUserEntity = changeStatus(id, UserStatus.ACTIVE)

    /** Активация учётной записи. */
    fun activate(id: Int): AppUserEntity = changeStatus(id, UserStatus.ACTIVE)

    /**
     * Удаление учётной записи с обезличиванием персональных данных (FR020).
     *
     * Запись физически не удаляется: она остаётся в базе, поскольку на неё
     * ссылаются созданные документы, этапы маршрута и листы ознакомления.
     * Персональные данные заменяются обезличенными значениями, пароль
     * сбрасывается, роль снижается до EMPLOYEE, статус становится DELETED.
     */
    @Transactional
    fun deleteUser(id: Int) {
        val user = findById(id) ?: throw IllegalArgumentException("Учётная запись не найдена")
        if (user.status == UserStatus.DELETED) {
            return
        }

        val originalName = user.fullName
        user.fullName = "Удалённый пользователь"
        user.email = "deleted-$id@anonymous.local"
        user.inn = null
        user.snils = null
        user.phone = null
        user.personnelNumber = null
        user.dateOfBirth = null
        user.role = UserRole.EMPLOYEE
        user.status = UserStatus.DELETED
        user.passwordHash = passwordEncoder.encode(generatePassword())
        userRepository.save(user)

        audit(
            AuditAction.USER_DELETED,
            "Учётная запись «$originalName» удалена с обезличиванием персональных данных",
            mapOf("исходное ФИО" to originalName)
        )
        log.info("Учётная запись id={} удалена с обезличиванием данных", id)
    }

    /**
     * Сброс пароля: система формирует новый временный пароль (FR020).
     */
    @Transactional
    fun resetPassword(id: Int): String {
        val user = findById(id) ?: throw IllegalArgumentException("Учётная запись не найдена")
        val password = generatePassword()
        user.passwordHash = passwordEncoder.encode(password)
        userRepository.save(user)

        audit(
            AuditAction.USER_UPDATED,
            "Пароль учётной записи «${user.fullName}» сброшен",
            mapOf("email" to user.email)
        )
        return password
    }

    // ------------------------------------------------------------------
    // Проверка данных
    // ------------------------------------------------------------------

    /**
     * Проверка полей формы по правилам доменов базы данных.
     *
     * @param userId идентификатор редактируемой учётной записи; null при создании.
     * @return карта «поле → текст ошибки»; пустая карта означает, что данные корректны.
     */
    fun validate(form: UserForm, userId: Int?): Map<String, String> {
        val errors = mutableMapOf<String, String>()

        val fullName = form.fullName.trim()
        if (fullName.isEmpty()) {
            errors["fullName"] = "Укажите ФИО"
        } else if (!FULL_NAME_PATTERN.matches(fullName)) {
            errors["fullName"] = "ФИО должно состоять из букв, пробелов и знаков «-», «(», «)», «,», «.»"
        }

        val email = form.email.trim().lowercase(Locale.ROOT)
        when {
            email.isEmpty() -> errors["email"] = "Укажите адрес электронной почты"
            !EMAIL_PATTERN.matches(email) -> errors["email"] = "Некорректный адрес электронной почты"
            userRepository.existsByEmailIgnoreCase(email) &&
                userRepository.findByEmailIgnoreCase(email)?.id != userId ->
                errors["email"] = "Учётная запись с таким адресом уже существует"
        }

        val personnelNumber = form.personnelNumber.trim()
        if (personnelNumber.isNotEmpty()) {
            if (!PERSONNEL_NUMBER_PATTERN.matches(personnelNumber)) {
                errors["personnelNumber"] = "Табельный номер: до 8 букв или цифр, через дефис — ещё до 8 символов"
            } else if (userRepository.existsByPersonnelNumber(personnelNumber) &&
                userRepository.findByPersonnelNumber(personnelNumber)?.id != userId
            ) {
                errors["personnelNumber"] = "Табельный номер уже используется"
            }
        }

        val inn = form.inn.trim()
        if (inn.isNotEmpty()) {
            if (!INN_PATTERN.matches(inn)) {
                errors["inn"] = "ИНН должен состоять из 12 цифр"
            } else if (userRepository.existsByInn(inn) && userRepository.findByInn(inn)?.id != userId) {
                errors["inn"] = "ИНН уже используется"
            }
        }

        val snils = form.snils.trim()
        if (snils.isNotEmpty()) {
            if (!SNILS_PATTERN.matches(snils)) {
                errors["snils"] = "СНИЛС: 11 цифр или формат XXX-XXX-XXX XX"
            } else if (userRepository.existsBySnils(snils) && userRepository.findBySnils(snils)?.id != userId) {
                errors["snils"] = "СНИЛС уже используется"
            }
        }

        val phone = form.phone.trim()
        if (phone.isNotEmpty()) {
            val normalized = normalizePhone(phone)
            if (normalized == null) {
                errors["phone"] = "Телефон должен содержать 10 или 11 цифр"
            } else if (userRepository.existsByPhone(normalized) &&
                userRepository.findByPhone(normalized)?.id != userId
            ) {
                errors["phone"] = "Телефон уже используется"
            }
        }

        val dateOfBirth = form.dateOfBirth.trim()
        if (dateOfBirth.isNotEmpty() && parseDate(dateOfBirth) == null) {
            errors["dateOfBirth"] = "Дата рождения в формате ДД.ММ.ГГГГ"
        }

        if (form.departmentId.isBlank()) {
            errors["departmentId"] = "Выберите подразделение"
        } else if (findDepartment(form.departmentId) == null) {
            errors["departmentId"] = "Подразделение не найдено"
        }

        if (form.positionId.isBlank()) {
            errors["positionId"] = "Выберите должность"
        } else if (findPosition(form.positionId) == null) {
            errors["positionId"] = "Должность не найдена"
        }

        val role = UserRole.entries.firstOrNull { it.name.equals(form.role.trim(), ignoreCase = true) }
        if (role == null) {
            errors["role"] = "Выберите роль"
        }

        val status = UserStatus.entries.firstOrNull { it.name.equals(form.status.trim(), ignoreCase = true) }
        if (status == null || status == UserStatus.DELETED) {
            errors["status"] = "Выберите статус"
        }

        val password = form.password.trim()
        if (password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH) {
            errors["password"] = "Пароль должен содержать не менее $MIN_PASSWORD_LENGTH символов"
        }

        return errors
    }

    // ------------------------------------------------------------------
    // Вспомогательные методы
    // ------------------------------------------------------------------

    private fun findDepartment(id: String): DepartmentEntity? =
        id.trim().toIntOrNull()?.let { departmentRepository.findById(it).orElse(null) }

    private fun findPosition(id: String): PositionEntity? =
        id.trim().toIntOrNull()?.let { positionRepository.findById(it).orElse(null) }

    private fun parseRole(value: String): UserRole =
        UserRole.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: UserRole.EMPLOYEE

    private fun parseStatus(value: String): UserStatus =
        UserStatus.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: UserStatus.CREATED

    /** Дата рождения принимается в формате ДД.ММ.ГГГГ или ГГГГ-ММ-ДД. */
    private fun parseDate(value: String): LocalDate? {
        val text = value.trim()
        if (text.isEmpty()) return null
        listOf(DateTimeFormatter.ofPattern("dd.MM.uuuu"), DateTimeFormatter.ISO_LOCAL_DATE).forEach { formatter ->
            try {
                return LocalDate.parse(text, formatter)
            } catch (ignored: DateTimeParseException) {
                // пробуем следующий формат
            }
        }
        return null
    }

    /**
     * Приведение телефона к маске +7 XXX XXX-XX-XX.
     *
     * Логика повторяет функцию f_normalize_phone базы данных.
     *
     * @return нормализованный номер или null, если разобрать не удалось.
     */
    fun normalizePhone(raw: String): String? {
        var digits = raw.filter { it.isDigit() }
        if (digits.length == 11 && digits.startsWith("8")) {
            digits = "7" + digits.substring(1)
        }
        if (digits.length == 10) {
            digits = "7" + digits
        }
        if (digits.length != 11 || !digits.startsWith("7")) {
            return null
        }
        val rest = digits.substring(1)
        return "+7 ${rest.substring(0, 3)} ${rest.substring(3, 6)}-${rest.substring(6, 8)}-${rest.substring(8, 10)}"
    }

    /** Формирование временного пароля. */
    fun generatePassword(): String {
        val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..PASSWORD_LENGTH)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")
    }

    /** Запись в журнал аудита (FR080). */
    private fun audit(action: AuditAction, comment: String, changed: Map<String, Any?>) {
        auditLogRepository.save(
            AuditLogEntity(
                user = currentUserService.currentUser(),
                action = action,
                comment = comment,
                changedFields = changed
            )
        )
    }

    /** Результат создания учётной записи. */
    data class CreatedUser(val user: AppUserEntity, val password: String)

    companion object {
        private const val PASSWORD_LENGTH = 10
        private const val MIN_PASSWORD_LENGTH = 6

        private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private val INN_PATTERN = Regex("^[0-9]{12}$")
        private val SNILS_PATTERN = Regex("^([0-9]{11}|[0-9]{3}-[0-9]{3}-[0-9]{3} [0-9]{2})$")
        private val PERSONNEL_NUMBER_PATTERN = Regex("^[А-ЯЁA-Z0-9]{1,8}(-[0-9А-ЯЁA-Z]{1,8})?$")
        private val FULL_NAME_PATTERN = Regex("^[А-ЯЁа-яёA-Za-z][А-ЯЁа-яёA-Za-z .,()\\-]{1,149}$")
    }
}
