-- =====================================================================
-- ИС КЭДО — объекты базы данных: триггеры, представления, функции
--
-- Шаг «Создание базы данных в СУБД», часть 2 (целостность и аналитика).
--
-- Соответствие требованиям из документа «Требования к содержанию НИРС»:
--   a) вычисление показателей и использование вычисляемых полей
--      → вычисляемые поля таблиц document и acquaintance_mark
--        (созданы в 01_schema.sql) + функции f_forecast_vacation_exhaustion
--        и f_best_worst, использующие их в запросах;
--   b) подсчёт итоговых результатов с автоматическим пересчётом
--      → материализованное представление mv_stat_document_totals
--        и триггер trg_refresh_stat_views;
--   c) подсчёт частных итогов с автоматическим пересчётом
--      → материализованное представление mv_stat_department_totals
--        и тот же триггер; счётчики листа ознакомления
--        (total_expected, acknowledged_count) пересчитывает trg_sheet_counters;
--   d) выбор трёх лучших и трёх худших экземпляров по показателю
--      → функция f_best_worst() и представления v_top3_*, v_bottom3_*;
--   e) выбор экземпляров по не менее чем двум условиям из разных таблиц
--      → представление v_overdue_documents (document + route_step
--        + app_users + department);
--   f) прогноз обнуления параметра и границы доверительного интервала
--      → функция f_forecast_vacation_exhaustion();
--   g) модифицированные запросы (CTE) для удаления, добавления и обновления
--      → f_archive_signed_documents() — UPDATE,
--        f_create_acquaintance_marks() — INSERT,
--        f_delete_stale_drafts() — DELETE.
--
-- Порядок выполнения: после sql/01_schema.sql.
-- =====================================================================

SET client_encoding = 'UTF8';
SET search_path TO public;

-- ---------------------------------------------------------------------
-- 1. ФУНКЦИИ СЛУЖЕБНОГО НАЗНАЧЕНИЯ
-- ---------------------------------------------------------------------

-- Приведение телефона к маске +7 XXX XXX-XX-XX.
-- Если разобрать номер не удалось, возвращается исходная строка —
-- тогда сработает ограничение домена d_phone и пользователь получит
-- понятное сообщение об ошибке ввода.
CREATE OR REPLACE FUNCTION f_normalize_phone(p_phone VARCHAR)
RETURNS VARCHAR
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    v_digits VARCHAR;
    v_rest   VARCHAR;
BEGIN
    IF p_phone IS NULL THEN
        RETURN NULL;
    END IF;

    v_digits := regexp_replace(p_phone, '\D', '', 'g');

    -- 8XXXXXXXXXX -> 7XXXXXXXXXX
    IF v_digits ~ '^8[0-9]{10}$' THEN
        v_digits := '7' || substring(v_digits FROM 2);
    END IF;

    -- 10 цифр без кода страны -> добавляем 7
    IF v_digits ~ '^[0-9]{10}$' THEN
        v_digits := '7' || v_digits;
    END IF;

    IF v_digits ~ '^7[0-9]{10}$' THEN
        v_rest := substring(v_digits FROM 2);
        RETURN '+7 ' || substring(v_rest FROM 1 FOR 3) || ' '
                    || substring(v_rest FROM 4 FOR 3) || '-'
                    || substring(v_rest FROM 7 FOR 2) || '-'
                    || substring(v_rest FROM 9 FOR 2);
    END IF;

    -- Привести не удалось — значение отклонит CHECK-условие ck_users_phone
    RETURN p_phone;
END $$;

COMMENT ON FUNCTION f_normalize_phone(VARCHAR) IS 'Приводит номер телефона к маске +7 XXX XXX-XX-XX';

-- ---------------------------------------------------------------------
-- 2. МАТЕРИАЛИЗОВАННЫЕ ПРЕДСТАВЛЕНИЯ — ИТОГОВЫЕ И ЧАСТНЫЕ ИТОГИ
-- ---------------------------------------------------------------------

-- Итоговые результаты по видам документов (требование «b»)
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_stat_document_totals AS
SELECT
    t.type_id,
    t.code                                                   AS type_code,
    t.name                                                   AS type_name,
    t.category                                               AS type_category,
    COUNT(d.doc_id)                                          AS total_documents,
    COUNT(d.doc_id) FILTER (WHERE d.status = 'DRAFT')        AS draft_count,
    COUNT(d.doc_id) FILTER (WHERE d.status = 'IN_APPROVAL')  AS in_approval_count,
    COUNT(d.doc_id) FILTER (WHERE d.status = 'REWORK')       AS rework_count,
    COUNT(d.doc_id) FILTER (WHERE d.status = 'APPROVED')     AS approved_count,
    COUNT(d.doc_id) FILTER (WHERE d.status = 'SIGNED')       AS signed_count,
    COUNT(d.doc_id) FILTER (WHERE d.status = 'REJECTED')     AS rejected_count,
    COUNT(d.doc_id) FILTER (WHERE d.status = 'RECALLED')     AS recalled_count,
    COUNT(d.doc_id) FILTER (WHERE d.status = 'ARCHIVED')     AS archived_count,
    COALESCE(SUM(d.vacation_days), 0)                        AS total_vacation_days,
    COALESCE(ROUND(AVG(d.vacation_days), 2), 0)              AS avg_vacation_days
FROM doc_type t
LEFT JOIN document d ON d.type_id = t.type_id
GROUP BY t.type_id, t.code, t.name, t.category;

COMMENT ON MATERIALIZED VIEW mv_stat_document_totals IS 'Итоговые результаты по видам документов. Пересчитывается триггером trg_refresh_stat_views';

CREATE UNIQUE INDEX IF NOT EXISTS ux_mv_stat_document_totals
    ON mv_stat_document_totals (type_id);

-- Частные итоги по подразделениям (требование «c»)
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_stat_department_totals AS
SELECT
    dp.dept_id,
    dp.code                                                       AS dept_code,
    dp.name                                                       AS dept_name,
    dp.level                                                      AS dept_level,
    COUNT(DISTINCT d.doc_id)                                      AS documents_total,
    COUNT(DISTINCT d.doc_id) FILTER (WHERE d.status = 'IN_APPROVAL') AS documents_in_approval,
    COUNT(DISTINCT d.doc_id) FILTER (WHERE d.status = 'SIGNED')   AS documents_signed,
    COUNT(DISTINCT d.doc_id) FILTER (WHERE d.status = 'ARCHIVED') AS documents_archived,
    COUNT(DISTINCT s.step_id)                                     AS steps_total,
    COUNT(DISTINCT s.step_id) FILTER (WHERE s.status = 'APPROVED') AS steps_approved,
    COUNT(DISTINCT s.step_id) FILTER (WHERE s.status = 'REJECTED') AS steps_rejected,
    COUNT(DISTINCT s.step_id) FILTER (WHERE s.status IN ('PENDING', 'IN_PROGRESS')
                                         AND s.deadline IS NOT NULL
                                         AND s.deadline < now())   AS steps_overdue,
    COUNT(DISTINCT am.mark_id)                                    AS marks_total,
    COUNT(DISTINCT am.mark_id) FILTER (WHERE am.status = 'ACKNOWLEDGED') AS marks_acknowledged,
    COALESCE(ROUND(AVG(d.vacation_days), 2), 0)                   AS avg_vacation_days
FROM department dp
LEFT JOIN app_users u            ON u.dept_id = dp.dept_id
LEFT JOIN document d             ON d.author_id = u.user_id
LEFT JOIN route_step s           ON s.doc_id = d.doc_id
LEFT JOIN acquaintance_sheet sh  ON sh.doc_id = d.doc_id
LEFT JOIN acquaintance_mark am   ON am.sheet_id = sh.sheet_id
GROUP BY dp.dept_id, dp.code, dp.name, dp.level;

COMMENT ON MATERIALIZED VIEW mv_stat_department_totals IS 'Частные итоги по подразделениям. Пересчитывается триггером trg_refresh_stat_views';

CREATE UNIQUE INDEX IF NOT EXISTS ux_mv_stat_department_totals
    ON mv_stat_department_totals (dept_id);

-- ---------------------------------------------------------------------
-- 3. ОБЫЧНЫЕ ПРЕДСТАВЛЕНИЯ
-- ---------------------------------------------------------------------

-- Требование «e»: выбор документов по не менее чем двум условиям,
-- данные для которых находятся в разных таблицах
-- (document + route_step + app_users + department).
CREATE OR REPLACE VIEW v_overdue_documents AS
SELECT
    d.doc_id,
    d.number,
    d.title,
    d.status                AS document_status,
    d.created_at,
    dp.dept_id,
    dp.name                 AS dept_name,
    u.user_id               AS approver_id,
    u.full_name             AS approver_name,
    s.step_id,
    s.step_order,
    s.deadline,
    (now() - s.deadline)    AS overdue_by
FROM document d
JOIN route_step s    ON s.doc_id = d.doc_id
JOIN app_users u     ON u.user_id = s.user_id
LEFT JOIN department dp ON dp.dept_id = u.dept_id
WHERE s.status IN ('PENDING', 'IN_PROGRESS')
  AND s.deadline IS NOT NULL
  AND s.deadline < now();

COMMENT ON VIEW v_overdue_documents IS 'Документы с просроченным этапом согласования: соединение document, route_step, app_users, department';

-- Требование «d»: три лучших согласующих по нагрузке
CREATE OR REPLACE VIEW v_top3_approvers_load AS
SELECT
    u.user_id,
    u.full_name,
    dp.name AS dept_name,
    COUNT(s.step_id) AS steps_total,
    COUNT(s.step_id) FILTER (WHERE s.status = 'APPROVED') AS steps_approved,
    COUNT(s.step_id) FILTER (WHERE s.status = 'REJECTED') AS steps_rejected,
    ROUND(COALESCE(AVG(EXTRACT(EPOCH FROM (s.decided_at - s.deadline)) / 3600.0), 0)::numeric, 2)
        AS avg_delay_hours
FROM route_step s
JOIN app_users u ON u.user_id = s.user_id
LEFT JOIN department dp ON dp.dept_id = u.dept_id
GROUP BY u.user_id, u.full_name, dp.name
ORDER BY steps_total DESC, avg_delay_hours ASC
LIMIT 3;

COMMENT ON VIEW v_top3_approvers_load IS 'Три лучших согласующих по количеству обработанных этапов';

-- Требование «d»: три худших согласующих по средней задержке решения
CREATE OR REPLACE VIEW v_bottom3_approvers_delay AS
SELECT
    u.user_id,
    u.full_name,
    dp.name AS dept_name,
    COUNT(s.step_id) AS steps_total,
    ROUND(COALESCE(AVG(EXTRACT(EPOCH FROM (s.decided_at - s.deadline)) / 3600.0), 0)::numeric, 2)
        AS avg_delay_hours
FROM route_step s
JOIN app_users u ON u.user_id = s.user_id
LEFT JOIN department dp ON dp.dept_id = u.dept_id
WHERE s.decided_at IS NOT NULL AND s.deadline IS NOT NULL
  AND s.status IN ('APPROVED', 'REJECTED', 'RETURNED')
GROUP BY u.user_id, u.full_name, dp.name
ORDER BY avg_delay_hours DESC, steps_total DESC
LIMIT 3;

COMMENT ON VIEW v_bottom3_approvers_delay IS 'Три худших согласующих по средней задержке принятия решения';

-- Требование «d»: три лучших и три худших подразделения по среднему сроку согласования
CREATE OR REPLACE VIEW v_top3_departments_speed AS
SELECT
    dp.dept_id,
    dp.name AS dept_name,
    COUNT(d.doc_id) AS documents_total,
    ROUND(COALESCE(AVG(EXTRACT(EPOCH FROM (d.updated_at - d.created_at)) / 3600.0), 0)::numeric, 2)
        AS avg_cycle_hours
FROM department dp
LEFT JOIN app_users u ON u.dept_id = dp.dept_id
LEFT JOIN document d  ON d.author_id = u.user_id
GROUP BY dp.dept_id, dp.name
HAVING COUNT(d.doc_id) > 0
ORDER BY avg_cycle_hours ASC
LIMIT 3;

CREATE OR REPLACE VIEW v_bottom3_departments_speed AS
SELECT
    dp.dept_id,
    dp.name AS dept_name,
    COUNT(d.doc_id) AS documents_total,
    ROUND(COALESCE(AVG(EXTRACT(EPOCH FROM (d.updated_at - d.created_at)) / 3600.0), 0)::numeric, 2)
        AS avg_cycle_hours
FROM department dp
LEFT JOIN app_users u ON u.dept_id = dp.dept_id
LEFT JOIN document d  ON d.author_id = u.user_id
GROUP BY dp.dept_id, dp.name
HAVING COUNT(d.doc_id) > 0
ORDER BY avg_cycle_hours DESC
LIMIT 3;

-- Сводка по листу ознакомления (FR115, UC090)
CREATE OR REPLACE VIEW v_acquaintance_summary AS
SELECT
    sh.sheet_id,
    sh.doc_id,
    d.number,
    d.title,
    sh.audience_type,
    sh.deadline,
    sh.status,
    sh.total_expected,
    sh.acknowledged_count,
    (sh.total_expected - sh.acknowledged_count) AS pending_count,
    CASE WHEN sh.total_expected = 0 THEN 0
         ELSE ROUND(sh.acknowledged_count * 100.0 / sh.total_expected, 1)
    END AS percent_complete
FROM acquaintance_sheet sh
JOIN document d ON d.doc_id = sh.doc_id;

COMMENT ON VIEW v_acquaintance_summary IS 'Сводка по листу ознакомления: сколько ознакомились и сколько осталось';

-- ---------------------------------------------------------------------
-- 4. ТРИГГЕРЫ ЦЕЛОСТНОСТИ И АВТОМАТИЧЕСКОГО ПЕРЕСЧЁТА
-- ---------------------------------------------------------------------

-- Скрипт можно выполнять повторно, поэтому существующие триггеры удаляются
DROP TRIGGER IF EXISTS trg_guard_signed_document          ON document;
DROP TRIGGER IF EXISTS trg_set_updated_at                 ON document;
DROP TRIGGER IF EXISTS trg_assign_document_number         ON document;
DROP TRIGGER IF EXISTS trg_refresh_stat_document_totals   ON document;
DROP TRIGGER IF EXISTS trg_guard_status_transition        ON document;
DROP TRIGGER IF EXISTS trg_audit_immutable                ON audit_log;
DROP TRIGGER IF EXISTS trg_department_tree                ON department;
DROP TRIGGER IF EXISTS trg_normalize_user                 ON app_users;
DROP TRIGGER IF EXISTS trg_check_attachment_total_size    ON attachment;
DROP TRIGGER IF EXISTS trg_refresh_stat_department_totals ON route_step;
DROP TRIGGER IF EXISTS trg_refresh_stat_marks_totals      ON acquaintance_mark;
DROP TRIGGER IF EXISTS trg_sheet_counters                 ON acquaintance_mark;

-- TR040: подписанный документ не изменяется и не удаляется.
-- Допускается только перевод в архив.
CREATE OR REPLACE FUNCTION trg_guard_signed_document()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('SIGNED', 'ARCHIVED') THEN
            RAISE EXCEPTION 'Документ % находится в статусе "%" и не может быть удалён',
                OLD.number, OLD.status;
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status IN ('SIGNED', 'ARCHIVED')
       AND NEW.status IS NOT DISTINCT FROM OLD.status
       AND NEW.content_json IS NOT DISTINCT FROM OLD.content_json
       AND NEW.title IS NOT DISTINCT FROM OLD.title THEN
        RAISE EXCEPTION 'Документ % подписан и не может быть изменён', OLD.number;
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_guard_signed_document
    BEFORE UPDATE OR DELETE ON document
    FOR EACH ROW EXECUTE FUNCTION trg_guard_signed_document();

-- Матрица допустимых переходов статуса документа.
-- Правило проверяется на уровне БД, поэтому неверный переход невозможен
-- даже при обращении к базе в обход приложения.
CREATE OR REPLACE FUNCTION trg_guard_status_transition()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;

    IF NOT (
        (OLD.status = 'DRAFT'      AND NEW.status IN ('IN_APPROVAL', 'RECALLED')) OR
        (OLD.status = 'IN_APPROVAL' AND NEW.status IN ('REWORK', 'APPROVED', 'REJECTED', 'RECALLED', 'SIGNED')) OR
        (OLD.status = 'REWORK'      AND NEW.status IN ('DRAFT', 'IN_APPROVAL', 'RECALLED')) OR
        (OLD.status = 'APPROVED'    AND NEW.status IN ('SIGNED', 'REJECTED', 'ARCHIVED')) OR
        (OLD.status = 'SIGNED'      AND NEW.status = 'ARCHIVED') OR
        (OLD.status = 'REJECTED'    AND NEW.status IN ('DRAFT', 'RECALLED')) OR
        (OLD.status = 'RECALLED'    AND NEW.status = 'DRAFT')
    ) THEN
        RAISE EXCEPTION 'Недопустимый переход статуса документа: "%" -> "%"', OLD.status, NEW.status;
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_guard_status_transition
    BEFORE UPDATE OF status ON document
    FOR EACH ROW EXECUTE FUNCTION trg_guard_status_transition();

-- TR040: записи журнала аудита не изменяются и не удаляются
CREATE OR REPLACE FUNCTION trg_audit_immutable()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Записи журнала аудита не изменяются и не удаляются (требование TR040)';
END $$;

CREATE TRIGGER trg_audit_immutable
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION trg_audit_immutable();

-- Автоматическое обновление даты изменения документа
CREATE OR REPLACE FUNCTION trg_set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $$;

CREATE TRIGGER trg_set_updated_at
    BEFORE UPDATE ON document
    FOR EACH ROW EXECUTE FUNCTION trg_set_updated_at();

-- Поддержание иерархии подразделений: путь и уровень
CREATE OR REPLACE FUNCTION trg_department_tree()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_parent_path  VARCHAR;
    v_parent_level INTEGER;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.parent_dept_id IS DISTINCT FROM OLD.parent_dept_id THEN
        IF EXISTS (SELECT 1 FROM department WHERE parent_dept_id = NEW.dept_id) THEN
            RAISE EXCEPTION 'Нельзя изменить родителя подразделения "%", у которого есть подчинённые подразделения',
                OLD.name;
        END IF;
    END IF;

    IF NEW.parent_dept_id IS NULL THEN
        NEW.path  := '/' || NEW.dept_id || '/';
        NEW.level := 0;
    ELSE
        SELECT d.path, d.level INTO v_parent_path, v_parent_level
        FROM department d
        WHERE d.dept_id = NEW.parent_dept_id;

        IF v_parent_path IS NULL THEN
            RAISE EXCEPTION 'Подразделение-родитель с идентификатором % не найдено', NEW.parent_dept_id;
        END IF;

        NEW.path  := v_parent_path || NEW.dept_id || '/';
        NEW.level := v_parent_level + 1;
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_department_tree
    BEFORE INSERT OR UPDATE OF parent_dept_id, dept_id ON department
    FOR EACH ROW EXECUTE FUNCTION trg_department_tree();

-- Автоматическая регистрация номера документа
CREATE OR REPLACE FUNCTION trg_assign_document_number()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_prefix VARCHAR;
    v_year   INTEGER;
    v_next   INTEGER;
BEGIN
    IF NEW.number IS NOT NULL AND btrim(NEW.number) <> '' THEN
        RETURN NEW;
    END IF;

    SELECT CASE t.category
               WHEN 'APPLICATION' THEN 'З'
               WHEN 'ORDER'      THEN 'П'
               WHEN 'LNA'        THEN 'ЛНА'
               WHEN 'MEMO'       THEN 'СЛ'
               ELSE 'Д'
           END
    INTO v_prefix
    FROM doc_type t
    WHERE t.type_id = NEW.type_id;

    v_prefix := COALESCE(v_prefix, 'Д');
    v_year   := EXTRACT(YEAR FROM now())::INTEGER;

    -- Блокировка на время транзакции гарантирует уникальность номера
    PERFORM pg_advisory_xact_lock(hashtext(v_prefix || '-' || v_year::TEXT));

    SELECT COALESCE(MAX(SUBSTRING(d.number FROM '[0-9]+$')::INTEGER), 0) + 1
    INTO v_next
    FROM document d
    WHERE d.number LIKE v_prefix || '-' || v_year::TEXT || '-%';

    NEW.number := v_prefix || '-' || v_year::TEXT || '-' || lpad(v_next::TEXT, 4, '0');

    RETURN NEW;
END $$;

CREATE TRIGGER trg_assign_document_number
    BEFORE INSERT ON document
    FOR EACH ROW EXECUTE FUNCTION trg_assign_document_number();

-- Нормализация реквизитов пользователя при вводе
CREATE OR REPLACE FUNCTION trg_normalize_user()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.email IS NOT NULL THEN
        NEW.email := lower(btrim(NEW.email));
    END IF;

    IF NEW.phone IS NOT NULL THEN
        NEW.phone := f_normalize_phone(NEW.phone);
    END IF;

    IF NEW.full_name IS NOT NULL THEN
        NEW.full_name := btrim(NEW.full_name);
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_normalize_user
    BEFORE INSERT OR UPDATE ON app_users
    FOR EACH ROW EXECUTE FUNCTION trg_normalize_user();

-- FR220: суммарный размер вложений одного документа — не более 100 МБ
CREATE OR REPLACE FUNCTION trg_check_attachment_total_size()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_total BIGINT;
BEGIN
    SELECT COALESCE(SUM(a.file_size), 0) + NEW.file_size
    INTO v_total
    FROM attachment a
    WHERE a.doc_id = NEW.doc_id;

    IF v_total > 104857600 THEN
        RAISE EXCEPTION 'Суммарный размер вложений документа превышает 100 МБ ( FR220 )';
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER trg_check_attachment_total_size
    BEFORE INSERT ON attachment
    FOR EACH ROW EXECUTE FUNCTION trg_check_attachment_total_size();

-- Требования «b» и «c»: автоматический пересчёт итогов при изменении данных
CREATE OR REPLACE FUNCTION trg_refresh_stat_views()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_matviews WHERE matviewname = 'mv_stat_document_totals') THEN
        REFRESH MATERIALIZED VIEW mv_stat_document_totals;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_matviews WHERE matviewname = 'mv_stat_department_totals') THEN
        REFRESH MATERIALIZED VIEW mv_stat_department_totals;
    END IF;

    RETURN NULL;
END $$;

CREATE TRIGGER trg_refresh_stat_document_totals
    AFTER INSERT OR UPDATE OR DELETE ON document
    FOR EACH ROW EXECUTE FUNCTION trg_refresh_stat_views();

CREATE TRIGGER trg_refresh_stat_department_totals
    AFTER INSERT OR UPDATE OR DELETE ON route_step
    FOR EACH ROW EXECUTE FUNCTION trg_refresh_stat_views();

CREATE TRIGGER trg_refresh_stat_marks_totals
    AFTER INSERT OR UPDATE OR DELETE ON acquaintance_mark
    FOR EACH ROW EXECUTE FUNCTION trg_refresh_stat_views();

-- Пересчёт счётчиков листа ознакомления
CREATE OR REPLACE FUNCTION trg_sheet_counters()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_sheet INTEGER := COALESCE(NEW.sheet_id, OLD.sheet_id);
BEGIN
    UPDATE acquaintance_sheet s
    SET total_expected     = (SELECT COUNT(*) FROM acquaintance_mark m WHERE m.sheet_id = v_sheet),
        acknowledged_count = (SELECT COUNT(*) FROM acquaintance_mark m
                               WHERE m.sheet_id = v_sheet AND m.status = 'ACKNOWLEDGED')
    WHERE s.sheet_id = v_sheet;

    RETURN NULL;
END $$;

CREATE TRIGGER trg_sheet_counters
    AFTER INSERT OR UPDATE OR DELETE ON acquaintance_mark
    FOR EACH ROW EXECUTE FUNCTION trg_sheet_counters();

-- ---------------------------------------------------------------------
-- 5. МОДИФИЦИРОВАННЫЕ ЗАПРОСЫ (CTE) — ТРЕБОВАНИЕ «g»
-- ---------------------------------------------------------------------

-- CTE + UPDATE: пакетный перевод подписанных документов в архив
CREATE OR REPLACE FUNCTION f_archive_signed_documents(p_before DATE)
RETURNS INTEGER
LANGUAGE plpgsql AS $$
DECLARE
    v_count INTEGER;
BEGIN
    WITH archived AS (
        UPDATE document
        SET status     = 'ARCHIVED',
            updated_at = now()
        WHERE status = 'SIGNED'
          AND created_at < p_before
        RETURNING doc_id
    )
    SELECT COUNT(*) INTO v_count FROM archived;

    RETURN v_count;
END $$;

COMMENT ON FUNCTION f_archive_signed_documents(DATE) IS 'Модифицированный запрос (CTE): пакетный перевод подписанных документов в архив. Возвращает количество архивированных документов';

-- CTE + INSERT: массовое создание отметок об ознакомлении по аудитории листа
CREATE OR REPLACE FUNCTION f_create_acquaintance_marks(p_sheet_id INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql AS $$
DECLARE
    v_count INTEGER;
BEGIN
    WITH sheet AS (
        SELECT * FROM acquaintance_sheet WHERE sheet_id = p_sheet_id
    ),
    audience AS (
        SELECT u.user_id
        FROM app_users u, sheet s
        WHERE u.status = 'ACTIVE' AND s.audience_type = 'ALL'
        UNION
        SELECT u.user_id
        FROM app_users u
        JOIN department dp ON dp.dept_id = u.dept_id, sheet s
        WHERE u.status = 'ACTIVE' AND s.audience_type = 'DEPARTMENT'
          AND dp.dept_id::TEXT = s.audience_ref
        UNION
        SELECT u.user_id
        FROM app_users u
        JOIN position p ON p.position_id = u.position_id, sheet s
        WHERE u.status = 'ACTIVE' AND s.audience_type = 'POSITION'
          AND p.position_id::TEXT = s.audience_ref
        UNION
        SELECT u.user_id
        FROM app_users u, sheet s
        WHERE u.status = 'ACTIVE' AND s.audience_type = 'LIST'
          AND u.user_id::TEXT = ANY(string_to_array(s.audience_ref, ','))
    ),
    inserted AS (
        INSERT INTO acquaintance_mark (sheet_id, user_id, status)
        SELECT p_sheet_id, a.user_id, 'PENDING'
        FROM audience a
        WHERE NOT EXISTS (
            SELECT 1 FROM acquaintance_mark m
            WHERE m.sheet_id = p_sheet_id AND m.user_id = a.user_id
        )
        RETURNING mark_id
    )
    SELECT COUNT(*) INTO v_count FROM inserted;

    RETURN v_count;
END $$;

COMMENT ON FUNCTION f_create_acquaintance_marks(INTEGER) IS 'Модифицированный запрос (CTE): массовое создание отметок об ознакомлении для выбранной аудитории';

-- CTE + DELETE: удаление черновиков, которые так и не были отправлены
CREATE OR REPLACE FUNCTION f_delete_stale_drafts(p_before DATE)
RETURNS INTEGER
LANGUAGE plpgsql AS $$
DECLARE
    v_count INTEGER;
BEGIN
    WITH removable AS (
        DELETE FROM document d
        WHERE d.status = 'DRAFT'
          AND d.created_at < p_before
          AND NOT EXISTS (SELECT 1 FROM route_step s WHERE s.doc_id = d.doc_id)
          AND NOT EXISTS (SELECT 1 FROM document b WHERE b.base_doc_id = d.doc_id)
        RETURNING d.doc_id
    )
    SELECT COUNT(*) INTO v_count FROM removable;

    RETURN v_count;
END $$;

COMMENT ON FUNCTION f_delete_stale_drafts(DATE) IS 'Модифицированный запрос (CTE): удаление черновиков, не отправленных на согласование';

-- ---------------------------------------------------------------------
-- 6. АНАЛИТИЧЕСКИЕ ФУНКЦИИ — ТРЕБОВАНИЯ «d» и «f»
-- ---------------------------------------------------------------------

-- Требование «d»: три лучших и три худших экземпляра по показателю.
-- Показатель выбирается параметром:
--   APPROVER_DELAY       — средняя задержка решения согласующего, часы;
--   DEPARTMENT_DURATION  — средний срок согласования документов подразделения, часы.
CREATE OR REPLACE FUNCTION f_best_worst(p_metric VARCHAR DEFAULT 'APPROVER_DELAY')
RETURNS TABLE (
    metric     VARCHAR,
    kind       VARCHAR,
    place      INTEGER,
    subject_id INTEGER,
    subject    VARCHAR,
    value      NUMERIC
)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_metric VARCHAR := upper(COALESCE(p_metric, 'APPROVER_DELAY'));
BEGIN
    IF v_metric = 'DEPARTMENT_DURATION' THEN
        RETURN QUERY
        WITH stats AS (
            SELECT dp.dept_id        AS id,
                   dp.name::VARCHAR  AS subject,
                   ROUND(COALESCE(AVG(EXTRACT(EPOCH FROM (d.updated_at - d.created_at)) / 3600.0), 0)::numeric, 2) AS val
            FROM department dp
            JOIN app_users u ON u.dept_id = dp.dept_id
            JOIN document d  ON d.author_id = u.user_id
            GROUP BY dp.dept_id, dp.name
        ),
        ranked AS (
            SELECT s.*,
                   ROW_NUMBER() OVER (ORDER BY s.val ASC)  AS r_best,
                   ROW_NUMBER() OVER (ORDER BY s.val DESC) AS r_worst
            FROM stats s
        )
        SELECT v_metric,
               CASE WHEN r.r_best <= 3 THEN 'BEST' ELSE 'WORST' END::VARCHAR,
               CASE WHEN r.r_best <= 3 THEN r.r_best ELSE r.r_worst END::INTEGER,
               r.id, r.subject, r.val
        FROM ranked r
        WHERE r.r_best <= 3 OR r.r_worst <= 3
        ORDER BY CASE WHEN r.r_best <= 3 THEN 0 ELSE 1 END,
                 CASE WHEN r.r_best <= 3 THEN r.r_best ELSE r.r_worst END;

    ELSE
        RETURN QUERY
        WITH stats AS (
            SELECT u.user_id        AS id,
                   u.full_name::VARCHAR AS subject,
                   ROUND(COALESCE(AVG(EXTRACT(EPOCH FROM (s.decided_at - s.deadline)) / 3600.0), 0)::numeric, 2) AS val
            FROM route_step s
            JOIN app_users u ON u.user_id = s.user_id
            WHERE s.decided_at IS NOT NULL
              AND s.deadline IS NOT NULL
              AND s.status IN ('APPROVED', 'REJECTED', 'RETURNED')
            GROUP BY u.user_id, u.full_name
        ),
        ranked AS (
            SELECT s.*,
                   ROW_NUMBER() OVER (ORDER BY s.val ASC)  AS r_best,
                   ROW_NUMBER() OVER (ORDER BY s.val DESC) AS r_worst
            FROM stats s
        )
        SELECT v_metric,
               CASE WHEN r.r_best <= 3 THEN 'BEST' ELSE 'WORST' END::VARCHAR,
               CASE WHEN r.r_best <= 3 THEN r.r_best ELSE r.r_worst END::INTEGER,
               r.id, r.subject, r.val
        FROM ranked r
        WHERE r.r_best <= 3 OR r.r_worst <= 3
        ORDER BY CASE WHEN r.r_best <= 3 THEN 0 ELSE 1 END,
                 CASE WHEN r.r_best <= 3 THEN r.r_best ELSE r.r_worst END;
    END IF;
END $$;

COMMENT ON FUNCTION f_best_worst(VARCHAR) IS 'Требование «d»: выбор трёх лучших и трёх худших экземпляров по показателю';

-- Требование «f»: прогноз времени обнуления остатка отпускных дней
-- и границы его доверительного интервала (95 %).
--
-- Логика:
--   1) по накопленной истории оформленных заявлений на отпуск считается
--      среднее количество дней отпуска в месяц и его выборочное отклонение;
--   2) средняя скорость и её доверительный интервал:
--          rate ± t(0.975; n-1) * s / sqrt(n);
--   3) срок исчерпания остатка: T = остаток / скорость;
--   4) границы доверительного интервала для T подставляются по границам
--      интервала скорости (обратная зависимость).
CREATE OR REPLACE FUNCTION f_forecast_vacation_exhaustion(
    p_user_id INTEGER,
    p_entitlement NUMERIC DEFAULT 28
)
RETURNS TABLE (
    user_id              INTEGER,
    full_name            VARCHAR,
    used_days            NUMERIC,
    remaining_days       NUMERIC,
    monthly_rate         NUMERIC,
    months_to_exhaustion NUMERIC,
    forecast_date        DATE,
    lower_date           DATE,
    upper_date           DATE,
    confidence_level     NUMERIC
)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_name      VARCHAR;
    v_used      NUMERIC := 0;
    v_rate      NUMERIC := 0;
    v_sd        NUMERIC := 0;
    v_n         INTEGER := 0;
    v_t         NUMERIC := 1.96;
    v_rate_lo   NUMERIC := 0;
    v_rate_hi   NUMERIC := 0;
    v_remaining NUMERIC := 0;
    v_months    NUMERIC := 0;
    v_conf      NUMERIC := 0.95;
BEGIN
    SELECT u.full_name::VARCHAR INTO v_name
    FROM app_users u
    WHERE u.user_id = p_user_id;

    IF v_name IS NULL THEN
        RETURN;
    END IF;

    -- Использовано в текущем календарном году
    SELECT COALESCE(SUM(COALESCE(d.vacation_days, 0)), 0)
    INTO v_used
    FROM document d
    JOIN doc_type t ON t.type_id = d.type_id
    WHERE d.author_id = p_user_id
      AND t.category = 'APPLICATION'
      AND d.status IN ('APPROVED', 'SIGNED', 'ARCHIVED')
      AND d.period_end IS NOT NULL
      AND EXTRACT(YEAR FROM d.period_end) = EXTRACT(YEAR FROM CURRENT_DATE);

    -- Среднее количество дней отпуска в месяц за последние 12 месяцев
    WITH monthly AS (
        SELECT COALESCE(d.vacation_days, 0) AS y
        FROM document d
        JOIN doc_type t ON t.type_id = d.type_id
        WHERE d.author_id = p_user_id
          AND t.category = 'APPLICATION'
          AND d.status IN ('APPROVED', 'SIGNED', 'ARCHIVED')
          AND d.period_end IS NOT NULL
          AND d.period_end >= (CURRENT_DATE - INTERVAL '12 months')
    )
    SELECT COUNT(*), COALESCE(AVG(y), 0), COALESCE(STDDEV_SAMP(y), 0)
    INTO v_n, v_rate, v_sd
    FROM monthly;

    v_remaining := GREATEST(p_entitlement - v_used, 0);

    IF v_n >= 2 AND v_rate > 0 THEN
        v_t := CASE v_n - 1
                   WHEN 1  THEN 12.706 WHEN 2  THEN 4.303 WHEN 3  THEN 3.182
                   WHEN 4  THEN 2.776  WHEN 5  THEN 2.571 WHEN 6  THEN 2.447
                   WHEN 7  THEN 2.365  WHEN 8  THEN 2.306 WHEN 9  THEN 2.262
                   WHEN 10 THEN 2.228  WHEN 11 THEN 2.201 WHEN 12 THEN 2.179
                   ELSE 1.96
               END;

        v_rate_lo := GREATEST(v_rate - v_t * v_sd / SQRT(v_n), 0.0001);
        v_rate_hi := v_rate + v_t * v_sd / SQRT(v_n);

        v_months := v_remaining / v_rate;

        forecast_date := (CURRENT_DATE + (v_months * INTERVAL '1 month'))::DATE;
        lower_date    := (CURRENT_DATE + ((v_remaining / v_rate_hi) * INTERVAL '1 month'))::DATE;
        upper_date    := (CURRENT_DATE + ((v_remaining / v_rate_lo) * INTERVAL '1 month'))::DATE;
    ELSE
        v_months := NULL;
        forecast_date := NULL;
        lower_date := NULL;
        upper_date := NULL;
    END IF;

    RETURN QUERY
    SELECT p_user_id, v_name, v_used, v_remaining, v_rate, v_months,
           forecast_date, lower_date, upper_date, v_conf;
END $$;

COMMENT ON FUNCTION f_forecast_vacation_exhaustion(INTEGER, NUMERIC) IS 'Требование «f»: прогноз даты исчерпания остатка отпускных дней и границы доверительного интервала 95 %';

-- ---------------------------------------------------------------------
-- 7. ПЕРЕСЧЁТ ИТОГОВ ПРИ ЗАПУСКЕ
-- ---------------------------------------------------------------------

REFRESH MATERIALIZED VIEW mv_stat_document_totals;
REFRESH MATERIALIZED VIEW mv_stat_department_totals;
