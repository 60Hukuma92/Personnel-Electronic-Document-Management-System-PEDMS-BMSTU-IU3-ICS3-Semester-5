package bmstu.iu3.moiseev.pedms.config

import bmstu.iu3.moiseev.pedms.domain.DocCategory
import bmstu.iu3.moiseev.pedms.domain.StepAction
import bmstu.iu3.moiseev.pedms.domain.UserRole
import bmstu.iu3.moiseev.pedms.domain.UserStatus
import bmstu.iu3.moiseev.pedms.entity.AppUserEntity
import bmstu.iu3.moiseev.pedms.entity.DepartmentEntity
import bmstu.iu3.moiseev.pedms.entity.DocTypeEntity
import bmstu.iu3.moiseev.pedms.entity.OrganizationEntity
import bmstu.iu3.moiseev.pedms.entity.PositionEntity
import bmstu.iu3.moiseev.pedms.entity.RouteRuleEntity
import bmstu.iu3.moiseev.pedms.entity.UserSettingsEntity
import bmstu.iu3.moiseev.pedms.repository.AppUserRepository
import bmstu.iu3.moiseev.pedms.repository.DepartmentRepository
import bmstu.iu3.moiseev.pedms.repository.DocTypeRepository
import bmstu.iu3.moiseev.pedms.repository.OrganizationRepository
import bmstu.iu3.moiseev.pedms.repository.PositionRepository
import bmstu.iu3.moiseev.pedms.repository.RouteRuleRepository
import bmstu.iu3.moiseev.pedms.repository.UserSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Начальное наполнение справочников и демонстрационных учётных записей.
 *
 * Выполняется один раз при первом запуске: если в базе уже есть
 * организация, повторное наполнение пропускается, поэтому запуск
 * не перезаписывает данные, введённые пользователем.
 *
 * Наполняются:
 *   • организация и дерево подразделений;
 *   • справочник должностей;
 *   • справочник видов документов с шаблонами форм;
 *   • правила маршрутизации по видам документов;
 *   • демонстрационные учётные записи всех четырёх ролей.
 */
@Component
class DataSeeder(
    private val organizationRepository: OrganizationRepository,
    private val departmentRepository: DepartmentRepository,
    private val positionRepository: PositionRepository,
    private val docTypeRepository: DocTypeRepository,
    private val routeRuleRepository: RouteRuleRepository,
    private val userRepository: AppUserRepository,
    private val userSettingsRepository: UserSettingsRepository
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(DataSeeder::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

    @Transactional
    override fun run(vararg args: String) {
        if (organizationRepository.count() > 0) {
            log.info("Справочники уже заполнены — начальное наполнение пропускается")
            return
        }

        log.info("Начальное наполнение справочников ИС КЭДО...")

        val organization = organizationRepository.save(
            OrganizationEntity(
                name = "ООО «Технопром»",
                inn = "770123456789",
                kpp = "770101001",
                legalAddress = "105187, г. Москва, ул. Ленинская Слобода, д. 19"
            )
        )

        val positions = listOf("Инженер", "Ведущий инженер", "Руководитель отдела",
            "Специалист по кадрам", "Начальник отдела кадров", "Финансовый директор")
            .associateWith { name -> positionRepository.save(PositionEntity(name = name)) }

        val headOffice = departmentRepository.save(
            DepartmentEntity(
                organization = organization,
                name = "Головной офис",
                code = "HQ"
            )
        )
        val hrDepartment = departmentRepository.save(
            DepartmentEntity(
                organization = organization,
                parent = headOffice,
                name = "Отдел кадров",
                code = "HR"
            )
        )
        val designDepartment = departmentRepository.save(
            DepartmentEntity(
                organization = organization,
                parent = headOffice,
                name = "Отдел проектирования",
                code = "PRD"
            )
        )
        val devGroup = departmentRepository.save(
            DepartmentEntity(
                organization = organization,
                parent = designDepartment,
                name = "Группа разработки",
                code = "DEV"
            )
        )
        val financeDepartment = departmentRepository.save(
            DepartmentEntity(
                organization = organization,
                parent = headOffice,
                name = "Финансовый отдел",
                code = "FIN"
            )
        )

        // --- Демонстрационные учётные записи всех ролей ---------------------
        val employee = createUser(
            personnelNumber = "ИУ3-00001",
            fullName = "Петрова Анна Сергеевна",
            dateOfBirth = "1990-04-12",
            inn = "500100732259",
            snils = "001-001-001 00",
            phone = "+7 916 123-45-67",
            email = "petrova@pedms.ru",
            role = UserRole.EMPLOYEE,
            department = devGroup,
            position = positions.getValue("Инженер")
        )
        val manager = createUser(
            personnelNumber = "ИУ3-00002",
            fullName = "Иванов Иван Иванович",
            dateOfBirth = "1985-11-30",
            inn = "500100732260",
            snils = "001-001-001 01",
            phone = "+7 916 123-45-68",
            email = "ivanov@pedms.ru",
            role = UserRole.MANAGER,
            department = designDepartment,
            position = positions.getValue("Руководитель отдела")
        )
        val hrSpecialist = createUser(
            personnelNumber = "ИУ3-00003",
            fullName = "Сидорова Мария Петровна",
            dateOfBirth = "1988-02-14",
            inn = "500100732261",
            snils = "001-001-001 02",
            phone = "+7 916 123-45-69",
            email = "sidorova@pedms.ru",
            role = UserRole.HR,
            department = hrDepartment,
            position = positions.getValue("Специалист по кадрам")
        )
        val administrator = createUser(
            personnelNumber = "ИУ3-00004",
            fullName = "Кузьмин Артём Олегович",
            dateOfBirth = "1990-01-01",
            inn = "500100732262",
            snils = "001-001-001 03",
            phone = "+7 916 123-45-70",
            email = "admin@pedms.ru",
            role = UserRole.ADMIN,
            department = headOffice,
            position = positions.getValue("Инженер")
        )
        createUser(
            personnelNumber = "ИУ3-00005",
            fullName = "Орлова Екатерина Львовна",
            dateOfBirth = "1992-03-05",
            inn = "500100732263",
            snils = "001-001-001 04",
            phone = "+7 916 123-45-71",
            email = "orlova@pedms.ru",
            role = UserRole.EMPLOYEE,
            department = devGroup,
            position = positions.getValue("Ведущий инженер")
        )

        // Руководители подразделений (ссылка замыкает цикл
        // department.manager_id -> app_users.user_id -> department.dept_id)
        designDepartment.manager = manager
        departmentRepository.save(designDepartment)

        // --- Справочник видов документов -------------------------------------
        val vacationApplication = docTypeRepository.save(
            DocTypeEntity(
                code = "ZAYAVL_OTP",
                name = "Заявление на ежегодный оплачиваемый отпуск",
                category = DocCategory.APPLICATION,
                templateJson = vacationTemplate(),
                requiresApproval = true,
                requiresSignature = false,
                signatureType = "NONE",
                requiresAcquaintance = false,
                retentionYears = 5
            )
        )
        val unpaidApplication = docTypeRepository.save(
            DocTypeEntity(
                code = "ZAYAVL_BEZ_ZP",
                name = "Заявление на отпуск без сохранения заработной платы",
                category = DocCategory.APPLICATION,
                templateJson = vacationTemplate(),
                requiresApproval = true,
                requiresSignature = false,
                signatureType = "NONE",
                requiresAcquaintance = false,
                retentionYears = 5
            )
        )
        val serviceMemo = docTypeRepository.save(
            DocTypeEntity(
                code = "SLUZHEB_ZAP",
                name = "Служебная записка",
                category = DocCategory.MEMO,
                templateJson = mapOf(
                    "fields" to listOf(
                        field("memo_subject", "text", "Тема служебной записки", required = true, halfWidth = false),
                        field("memo_body", "textarea", "Содержание служебной записки", required = true, halfWidth = false)
                    )
                ),
                requiresApproval = true,
                requiresSignature = false,
                signatureType = "NONE",
                requiresAcquaintance = false,
                retentionYears = 5
            )
        )
        val vacationOrder = docTypeRepository.save(
            DocTypeEntity(
                code = "PRIKAZ_OTP",
                name = "Приказ о предоставлении отпуска",
                category = DocCategory.ORDER,
                templateJson = mapOf(
                    "fields" to listOf(
                        field("order_basis", "text", "Основание (номер заявления)", required = true, halfWidth = false),
                        field("order_period", "text", "Период отпуска", required = true, halfWidth = false),
                        field("order_reason", "textarea", "Причина предоставления отпуска", required = false, halfWidth = false)
                    )
                ),
                requiresApproval = true,
                requiresSignature = true,
                signatureType = "SIMPLE_EP",
                requiresAcquaintance = false,
                retentionYears = 5
            )
        )
        docTypeRepository.save(
            DocTypeEntity(
                code = "PRIKAZ_UVOL",
                name = "Приказ об увольнении",
                category = DocCategory.ORDER,
                templateJson = mapOf(
                    "fields" to listOf(
                        field("order_basis", "text", "Основание", required = true, halfWidth = false),
                        field("order_reason", "textarea", "Причина увольнения", required = true, halfWidth = false)
                    )
                ),
                requiresApproval = true,
                requiresSignature = true,
                signatureType = "SIMPLE_EP",
                requiresAcquaintance = false,
                retentionYears = 75
            )
        )
        docTypeRepository.save(
            DocTypeEntity(
                code = "LNA_PVPD",
                name = "Положение о защите персональных данных",
                category = DocCategory.LNA,
                templateJson = mapOf(
                    "fields" to listOf(
                        field("lna_title", "text", "Наименование акта", required = true, halfWidth = false),
                        field("lna_version", "text", "Редакция", required = false, halfWidth = true),
                        field("lna_effective_from", "date", "Дата вступления в силу", required = true, halfWidth = true)
                    )
                ),
                requiresApproval = true,
                requiresSignature = true,
                signatureType = "SIMPLE_EP",
                requiresAcquaintance = true,
                retentionYears = 75
            )
        )
        docTypeRepository.save(
            DocTypeEntity(
                code = "LNA_PITR",
                name = "Правила внутреннего трудового распорядка",
                category = DocCategory.LNA,
                templateJson = mapOf(
                    "fields" to listOf(
                        field("lna_title", "text", "Наименование акта", required = true, halfWidth = false),
                        field("lna_version", "text", "Редакция", required = false, halfWidth = true),
                        field("lna_effective_from", "date", "Дата вступления в силу", required = true, halfWidth = true)
                    )
                ),
                requiresApproval = true,
                requiresSignature = true,
                signatureType = "SIMPLE_EP",
                requiresAcquaintance = true,
                retentionYears = 75
            )
        )

        // --- Правила маршрутизации (FR050) -----------------------------------
        // Пустое approverDepartment означает «подразделение инициатора».
        listOf(vacationApplication, unpaidApplication).forEach { type ->
            routeRuleRepository.save(
                RouteRuleEntity(
                    type = type,
                    department = null,
                    initiatorPosition = null,
                    approverPosition = positions.getValue("Руководитель отдела"),
                    approverDepartment = null,
                    stepOrder = 1,
                    action = StepAction.APPROVE,
                    deadlineDays = 2
                )
            )
            routeRuleRepository.save(
                RouteRuleEntity(
                    type = type,
                    department = null,
                    initiatorPosition = null,
                    approverPosition = positions.getValue("Специалист по кадрам"),
                    approverDepartment = hrDepartment,
                    stepOrder = 2,
                    action = StepAction.APPROVE,
                    deadlineDays = 1
                )
            )
        }
        routeRuleRepository.save(
            RouteRuleEntity(
                type = serviceMemo,
                department = null,
                initiatorPosition = null,
                approverPosition = positions.getValue("Руководитель отдела"),
                approverDepartment = null,
                stepOrder = 1,
                action = StepAction.APPROVE,
                deadlineDays = 3
            )
        )
        routeRuleRepository.save(
            RouteRuleEntity(
                type = vacationOrder,
                department = null,
                initiatorPosition = null,
                approverPosition = positions.getValue("Специалист по кадрам"),
                approverDepartment = hrDepartment,
                stepOrder = 1,
                action = StepAction.SIGN,
                deadlineDays = 1
            )
        )

        log.info(
            "Справочники заполнены: организация «{}», подразделений — {}, видов документов — {}, " +
                "учётных записей — {}",
            organization.name,
            departmentRepository.count(),
            docTypeRepository.count(),
            userRepository.count()
        )
        log.info("Демонстрационные учётные записи: {}", listOf(employee, manager, hrSpecialist, administrator)
            .joinToString(", ") { "${it.email} (${it.role.title})" })
    }

    private fun createUser(
        personnelNumber: String,
        fullName: String,
        dateOfBirth: String,
        inn: String,
        snils: String,
        phone: String,
        email: String,
        role: UserRole,
        department: DepartmentEntity,
        position: PositionEntity
    ): AppUserEntity {
        val user = userRepository.save(
            AppUserEntity(
                department = department,
                position = position,
                personnelNumber = personnelNumber,
                fullName = fullName,
                dateOfBirth = java.time.LocalDate.parse(dateOfBirth),
                inn = inn,
                snils = snils,
                phone = phone,
                email = email,
                role = role,
                status = UserStatus.ACTIVE,
                passwordHash = passwordEncoder.encode(DEMO_PASSWORD)
            )
        )
        userSettingsRepository.save(UserSettingsEntity(userId = user.id))
        return user
    }

    private fun vacationTemplate(): Map<String, Any?> = mapOf(
        "fields" to listOf(
            field("start_date", "date", "Начало периода отпуска", required = true, halfWidth = true),
            field("end_date", "date", "Окончание периода отпуска", required = true, halfWidth = true),
            field(
                "vacation_type", "select", "Вид отпуска", required = true, halfWidth = false,
                options = listOf(
                    mapOf("value" to "annual", "label" to "Ежегодный оплачиваемый"),
                    mapOf("value" to "additional", "label" to "Дополнительный")
                )
            ),
            field("comment", "textarea", "Комментарий", required = false, halfWidth = false)
        )
    )

    private fun field(
        key: String,
        type: String,
        label: String,
        required: Boolean,
        halfWidth: Boolean,
        options: List<Map<String, String>> = emptyList()
    ): Map<String, Any?> = buildMap {
        put("key", key)
        put("type", type)
        put("label", label)
        put("required", required)
        put("halfWidth", halfWidth)
        if (options.isNotEmpty()) put("options", options)
    }

    companion object {
        /** Пароль всех демонстрационных учётных записей. */
        const val DEMO_PASSWORD = "pedms2026"
    }
}
