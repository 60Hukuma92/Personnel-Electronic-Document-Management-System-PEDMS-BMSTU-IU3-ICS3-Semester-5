package bmstu.iu3.moiseev.pedms.config

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Проверка состава базы данных при запуске приложения.
 *
 * Схема базы создаётся скриптами из каталога sql/, а Hibernate работает
 * в режиме ddl-auto=none и в базе ничего не изменяет. Поэтому после
 * подключения выполняется сверка состава схемы с ожидаемым:
 * таблицы, домены, представления, функции и триггеры.
 *
 * Если что-то отсутствует, в журнал выводится предупреждение с точным
 * указанием, какой скрипт нужно выполнить.
 */
@Component
@Order(1)
class SchemaVerifier(private val jdbcTemplate: JdbcTemplate) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SchemaVerifier::class.java)

    override fun run(args: ApplicationArguments) {
        val tables = present(
            "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "where n.nspname = 'public' and c.relkind = 'r'"
        )
        val matViews = present(
            "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "where n.nspname = 'public' and c.relkind = 'm'"
        )
        val views = present(
            "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace " +
                "where n.nspname = 'public' and c.relkind = 'v'"
        )
        val domains = present(
            "select t.typname from pg_type t join pg_namespace n on n.oid = t.typnamespace " +
                "where t.typtype = 'd' and n.nspname = 'public'"
        )
        val functions = present(
            "select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                "where n.nspname = 'public' and left(p.proname, 2) = 'f_'"
        )
        val triggers = present("select tgname from pg_trigger where not tgisinternal")

        val missingTables = EXPECTED_TABLES.filterNot { it in tables }
        val missingMatViews = EXPECTED_MAT_VIEWS.filterNot { it in matViews }
        val missingViews = EXPECTED_VIEWS.filterNot { it in views }
        val missingDomains = EXPECTED_DOMAINS.filterNot { it in domains }
        val missingFunctions = EXPECTED_FUNCTIONS.filterNot { it in functions }

        if (missingTables.isEmpty() && missingMatViews.isEmpty() && missingViews.isEmpty() &&
            missingDomains.isEmpty() && missingFunctions.isEmpty()
        ) {
            log.info(
                "Схема базы данных проверена: таблиц — {}, доменов — {}, представлений — {}, " +
                    "материализованных представлений — {}, функций — {}, триггеров — {}",
                tables.size, domains.size, views.size, matViews.size, functions.size, triggers.size
            )
            return
        }

        log.warn("Схема базы данных создана не полностью.")
        if (missingTables.isNotEmpty()) {
            log.warn("Отсутствуют таблицы: {}. Выполните sql/01_schema.sql", missingTables.joinToString())
        }
        if (missingDomains.isNotEmpty()) {
            log.warn("Отсутствуют домены: {}. Выполните sql/01_schema.sql", missingDomains.joinToString())
        }
        if (missingViews.isNotEmpty() || missingMatViews.isNotEmpty()) {
            log.warn(
                "Отсутствуют представления: {}. Выполните sql/02_objects.sql",
                (missingViews + missingMatViews).joinToString()
            )
        }
        if (missingFunctions.isNotEmpty()) {
            log.warn("Отсутствуют функции: {}. Выполните sql/02_objects.sql", missingFunctions.joinToString())
        }
        log.warn("Подробности: sql/00_reset.sql, sql/01_schema.sql, sql/02_objects.sql")
    }

    private fun present(sql: String): Set<String> =
        jdbcTemplate.queryForList(sql, String::class.java).toSet()

    companion object {
        /** 15 таблиц уровня данных (раздел 3.7 записки). */
        val EXPECTED_TABLES = listOf(
            "organization", "department", "position", "app_users", "user_settings",
            "delegation", "doc_type", "document", "route_rule", "route_step",
            "attachment", "acquaintance_sheet", "acquaintance_mark", "notification", "audit_log"
        )

        /** Домены — средства защиты от ошибок ручного ввода. */
        val EXPECTED_DOMAINS = listOf(
            "d_email", "d_inn", "d_snils", "d_phone", "d_personnel_number", "d_full_name"
        )

        /** Материализованные представления — итоговые и частные итоги (b, c). */
        val EXPECTED_MAT_VIEWS = listOf("mv_stat_document_totals", "mv_stat_department_totals")

        /** Обычные представления. */
        val EXPECTED_VIEWS = listOf(
            "v_overdue_documents", "v_top3_approvers_load", "v_bottom3_approvers_delay",
            "v_top3_departments_speed", "v_bottom3_departments_speed", "v_acquaintance_summary"
        )

        /** Функции аналитики и модифицированных запросов (d, f, g). */
        val EXPECTED_FUNCTIONS = listOf(
            "f_normalize_phone", "f_best_worst", "f_forecast_vacation_exhaustion",
            "f_archive_signed_documents", "f_create_acquaintance_marks", "f_delete_stale_drafts"
        )
    }
}
