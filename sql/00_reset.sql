-- =====================================================================
-- ИС КЭДО — полный сброс схемы базы данных
-- Назначение: удалить все объекты, созданные скриптами 01_schema.sql
--              и 02_objects.sql, чтобы схему можно было пересоздать
--              с нуля (например, при изменении структуры таблиц).
--
-- ВНИМАНИЕ: скрипт удаляет все данные ИС КЭДО без возможности восстановления.
--           Выполнять только на этапе разработки.
--
-- Порядок выполнения:
--   1) sql/00_reset.sql      — этот файл
--   2) sql/01_schema.sql      — домены, таблицы, индексы
--   3) sql/02_objects.sql     — триггеры, представления, функции
--   4) sql/99_smoke_test.sql  — проверка (опционально)
-- =====================================================================

-- Представления и функции
DROP VIEW  IF EXISTS v_top3_approvers_load            CASCADE;
DROP VIEW  IF EXISTS v_bottom3_departments_duration   CASCADE;
DROP VIEW  IF EXISTS v_overdue_documents              CASCADE;
DROP VIEW  IF EXISTS v_documents_total_stats          CASCADE;
DROP MATERIALIZED VIEW IF EXISTS mv_stat_document_totals   CASCADE;
DROP MATERIALIZED VIEW IF EXISTS mv_stat_department_totals CASCADE;

DROP FUNCTION IF EXISTS f_forecast_vacation_exhaustion(INTEGER) CASCADE;
DROP FUNCTION IF EXISTS f_lower_ru(TEXT)                CASCADE;
DROP FUNCTION IF EXISTS f_best_worst()                   CASCADE;
DROP FUNCTION IF EXISTS f_archive_signed_documents(DATE) CASCADE;
DROP FUNCTION IF EXISTS f_escalate_overdue_steps()       CASCADE;
DROP FUNCTION IF EXISTS f_refresh_stat_views()           CASCADE;
DROP FUNCTION IF EXISTS trg_set_updated_at()             CASCADE;
DROP FUNCTION IF EXISTS trg_guard_signed_document()      CASCADE;
DROP FUNCTION IF EXISTS trg_audit_immutable()            CASCADE;
DROP FUNCTION IF EXISTS trg_department_tree()            CASCADE;
DROP FUNCTION IF EXISTS trg_assign_document_number()     CASCADE;
DROP FUNCTION IF EXISTS trg_sheet_counters()             CASCADE;
DROP FUNCTION IF EXISTS trg_normalize_user()             CASCADE;

-- Таблицы (порядок обратный: сначала зависимые, потом родительские)
DROP TABLE IF EXISTS audit_log          CASCADE;
DROP TABLE IF EXISTS notification       CASCADE;
DROP TABLE IF EXISTS acquaintance_mark  CASCADE;
DROP TABLE IF EXISTS acquaintance_sheet CASCADE;
DROP TABLE IF EXISTS attachment         CASCADE;
DROP TABLE IF EXISTS route_step         CASCADE;
DROP TABLE IF EXISTS route_rule         CASCADE;
DROP TABLE IF EXISTS document           CASCADE;
DROP TABLE IF EXISTS doc_type           CASCADE;
DROP TABLE IF EXISTS delegation         CASCADE;
DROP TABLE IF EXISTS user_settings      CASCADE;
DROP TABLE IF EXISTS app_users          CASCADE;
DROP TABLE IF EXISTS position           CASCADE;
DROP TABLE IF EXISTS department         CASCADE;
DROP TABLE IF EXISTS organization       CASCADE;

-- Таблицы предыдущей версии схемы (создавались Hibernate автоматически).
-- В актуальной модели используются department / organization, поэтому
-- старые таблицы удаляются, чтобы в базе не осталось дублирующих сущностей.
DROP TABLE IF EXISTS departments        CASCADE;
DROP TABLE IF EXISTS organizations      CASCADE;

-- Домены
DROP DOMAIN IF EXISTS d_email;
DROP DOMAIN IF EXISTS d_inn;
DROP DOMAIN IF EXISTS d_snils;
DROP DOMAIN IF EXISTS d_personnel_number;
DROP DOMAIN IF EXISTS d_full_name;
DROP DOMAIN IF EXISTS d_doc_code;
-- Домен предыдущей версии скрипта: маска телефона вынесена в CHECK-условие
DROP DOMAIN IF EXISTS d_phone;
