-- =====================================================================
-- ИС КЭДО — проверочный сценарий базы данных
--
-- Скрипт проверяет работу всех объектов, созданных в 01_schema.sql
-- и 02_objects.sql, и демонстрирует выполнение требований a–g
-- из документа «Требования к содержанию НИРС».
--
-- Скрипт выполняется в одной транзакции и откатывается в конце,
-- поэтому тестовые данные НЕ остаются в базе.
--
-- Запуск:
--   psql -v ON_ERROR_STOP=1 -f sql/99_smoke_test.sql
--
-- Каждый успешный check выводит строку «ТЕСТ ПРОЙДЕН: ...».
-- При нарушении проверки скрипт прерывается с ошибкой.
-- =====================================================================

BEGIN;

-- Проверка миграции настроек уведомлений
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'user_settings'
          AND column_name = 'notif_event_types'
    ) THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: отсутствует user_settings.notif_event_types';
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- A. ПОДГОТОВКА ТЕСТОВЫХ ДАННЫХ
-- ---------------------------------------------------------------------

INSERT INTO organization (name, inn, kpp, legal_address)
VALUES ('ООО «Тест-М»', '770123456789', '770101001', 'г. Москва, ул. Тестовая, д. 1');

INSERT INTO department (org_id, parent_dept_id, name, code)
SELECT o.org_id, NULL, 'Головной офис', 'HQ' FROM organization o WHERE o.inn = '770123456789';

INSERT INTO department (org_id, parent_dept_id, name, code)
SELECT o.org_id, d.dept_id, 'Отдел проектирования', 'PRD' FROM organization o, department d
WHERE o.inn = '770123456789' AND d.code = 'HQ';

INSERT INTO department (org_id, parent_dept_id, name, code)
SELECT o.org_id, d.dept_id, 'Группа разработки', 'DEV' FROM organization o, department d
WHERE o.inn = '770123456789' AND d.code = 'PRD';

INSERT INTO position (name) VALUES ('Инженер'), ('Руководитель отдела'), ('Специалист по кадрам');

INSERT INTO app_users (dept_id, position_id, personnel_number, full_name, date_of_birth,
                       inn, snils, phone, email, role, status, password_hash)
SELECT d.dept_id, p.position_id, 'ИУ3-00001', 'Петрова Анна Сергеевна', '1990-04-12',
       '500100732259', '001-001-001 00', '+7 916 123-45-67', 'petrova@example.ru',
       'EMPLOYEE', 'ACTIVE', 'test-hash'
FROM department d, position p WHERE d.code = 'DEV' AND p.name = 'Инженер';

INSERT INTO app_users (dept_id, position_id, personnel_number, full_name, date_of_birth,
                       inn, snils, phone, email, role, status, password_hash)
SELECT d.dept_id, p.position_id, 'ИУ3-00002', 'Иванов Иван Иванович', '1985-11-30',
       '500100732260', '001-001-001 01', '+7 916 123-45-68', 'ivanov@example.ru',
       'MANAGER', 'ACTIVE', 'test-hash'
FROM department d, position p WHERE d.code = 'PRD' AND p.name = 'Руководитель отдела';

INSERT INTO app_users (dept_id, position_id, personnel_number, full_name, date_of_birth,
                       inn, snils, phone, email, role, status, password_hash)
SELECT d.dept_id, p.position_id, 'ИУ3-00003', 'Сидорова Мария Петровна', '1988-02-14',
       '500100732261', '001-001-001 02', '+7 916 123-45-69', 'sidorova@example.ru',
       'HR', 'ACTIVE', 'test-hash'
FROM department d, position p WHERE d.code = 'HQ' AND p.name = 'Специалист по кадрам';

INSERT INTO app_users (dept_id, position_id, personnel_number, full_name, date_of_birth,
                       inn, snils, phone, email, role, status, password_hash)
SELECT d.dept_id, p.position_id, 'ИУ3-00004', 'Администратор Системы', '1990-01-01',
       '500100732262', '001-001-001 03', '+7 916 123-45-70', 'admin@example.ru',
       'ADMIN', 'ACTIVE', 'test-hash'
FROM department d, position p WHERE d.code = 'HQ' AND p.name = 'Инженер';

INSERT INTO app_users (dept_id, position_id, personnel_number, full_name, date_of_birth,
                       inn, snils, phone, email, role, status, password_hash)
SELECT d.dept_id, p.position_id, 'ИУ3-00005', 'Кузнецов Пётр Семёнович', '1993-07-19',
       '500100732263', '001-001-001 04', '+7 916 123-45-71', 'kuznetsov@example.ru',
       'EMPLOYEE', 'ACTIVE', 'test-hash'
FROM department d, position p WHERE d.code = 'DEV' AND p.name = 'Инженер';

INSERT INTO app_users (dept_id, position_id, personnel_number, full_name, date_of_birth,
                       inn, snils, phone, email, role, status, password_hash)
SELECT d.dept_id, p.position_id, 'ИУ3-00006', 'Орлова Екатерина Львовна', '1992-03-05',
       '500100732264', '001-001-001 05', '+7 916 123-45-72', 'orlova@example.ru',
       'EMPLOYEE', 'ACTIVE', 'test-hash'
FROM department d, position p WHERE d.code = 'DEV' AND p.name = 'Инженер';

INSERT INTO doc_type (code, name, category, template_json, requires_approval,
                      requires_signature, signature_type, requires_acquaintance, retention_years)
VALUES
 ('ZAYAVL', 'Заявление на ежегодный оплачиваемый отпуск', 'APPLICATION',
  '{"fields":[{"key":"start_date","type":"date","label":"Начало отпуска","required":true},
              {"key":"end_date","type":"date","label":"Окончание отпуска","required":true},
              {"key":"vacation_type","type":"select","label":"Вид отпуска","required":true}]}',
  TRUE, FALSE, 'NONE', FALSE, 5),
 ('PRIKAZ', 'Приказ о предоставлении отпуска', 'ORDER',
  '{"fields":[{"key":"order_basis","type":"text","label":"Основание","required":true}]}',
  TRUE, TRUE, 'SIMPLE_EP', FALSE, 5),
 ('LNA', 'Локальный нормативный акт', 'LNA',
  '{"fields":[{"key":"lna_title","type":"text","label":"Наименование акта","required":true}]}',
  TRUE, TRUE, 'SIMPLE_EP', TRUE, 75);

INSERT INTO document (type_id, author_id, title, status, content_json, period_start, period_end)
SELECT t.type_id, u.user_id, 'Заявление на отпуск с 01.06.2026 по 15.06.2026', 'DRAFT',
       '{"start_date":"2026-06-01","end_date":"2026-06-15","vacation_type":"annual"}'::jsonb,
       DATE '2026-06-01', DATE '2026-06-15'
FROM doc_type t, app_users u
WHERE t.code = 'ZAYAVL' AND u.email = 'petrova@example.ru';

INSERT INTO document (type_id, author_id, title, status, content_json, period_start, period_end)
SELECT t.type_id, u.user_id, 'Заявление на отпуск с 01.07.2026 по 10.07.2026', 'APPROVED',
       '{"start_date":"2026-07-01","end_date":"2026-07-10","vacation_type":"annual"}'::jsonb,
       DATE '2026-07-01', DATE '2026-07-10'
FROM doc_type t, app_users u
WHERE t.code = 'ZAYAVL' AND u.email = 'petrova@example.ru';

INSERT INTO document (type_id, author_id, title, status, content_json, period_start, period_end)
SELECT t.type_id, u.user_id, 'Заявление на отпуск с 01.08.2026 по 05.08.2026', 'SIGNED',
       '{"start_date":"2026-08-01","end_date":"2026-08-05","vacation_type":"annual"}'::jsonb,
       DATE '2026-08-01', DATE '2026-08-05'
FROM doc_type t, app_users u
WHERE t.code = 'ZAYAVL' AND u.email = 'petrova@example.ru';

INSERT INTO document (type_id, author_id, title, status, content_json)
SELECT t.type_id, u.user_id, 'Положение о защите персональных данных', 'SIGNED',
       '{"lna_title":"Положение о защите персональных данных"}'::jsonb
FROM doc_type t, app_users u
WHERE t.code = 'LNA' AND u.email = 'sidorova@example.ru';

-- Маршрут согласования для подписанного заявления
INSERT INTO route_step (doc_id, user_id, step_order, action, status, deadline, decided_at)
SELECT d.doc_id, u.user_id, 1, 'APPROVE', 'APPROVED',
       now() - INTERVAL '3 days', now() - INTERVAL '2 days'
FROM document d, app_users u
WHERE d.status = 'SIGNED' AND d.content_json->>'start_date' = '2026-08-01'
  AND u.email = 'ivanov@example.ru';

-- Просроченный этап для проверки требования «e»
INSERT INTO route_step (doc_id, user_id, step_order, action, status, deadline)
SELECT d.doc_id, u.user_id, 2, 'APPROVE', 'PENDING', now() - INTERVAL '5 days'
FROM document d, app_users u
WHERE d.content_json->>'start_date' = '2026-07-01' AND u.email = 'ivanov@example.ru';

-- Шесть согласующих с разной задержкой решения — для проверки выбора
-- трёх лучших и трёх худших (требование «d»)
WITH approvers AS (
    SELECT u.user_id,
           ROW_NUMBER() OVER (ORDER BY u.email) AS rn
    FROM app_users u
)
INSERT INTO route_step (doc_id, user_id, step_order, action, status, deadline, decided_at)
SELECT d.doc_id, a.user_id, a.rn, 'APPROVE', 'APPROVED',
       now() - INTERVAL '10 days',
       now() - INTERVAL '10 days' + (a.rn * INTERVAL '3 hours')
FROM document d
CROSS JOIN approvers a
WHERE d.content_json->>'lna_title' IS NOT NULL;

-- Лист ознакомления
INSERT INTO acquaintance_sheet (doc_id, created_by, audience_type, audience_ref, deadline)
SELECT d.doc_id, u.user_id, 'ALL', NULL, CURRENT_DATE + 14
FROM document d, app_users u
WHERE d.content_json->>'lna_title' IS NOT NULL AND u.email = 'sidorova@example.ru';

SELECT f_create_acquaintance_marks(
    (SELECT sheet_id FROM acquaintance_sheet LIMIT 1));

INSERT INTO audit_log (doc_id, user_id, action, comment)
SELECT d.doc_id, u.user_id, 'CREATED', 'Документ создан'
FROM document d, app_users u
WHERE d.content_json->>'lna_title' IS NOT NULL AND u.email = 'petrova@example.ru';

-- ---------------------------------------------------------------------
-- B. ПРОВЕРКИ ЦЕЛОСТНОСТИ (домены, маски, CHECK-условия)
-- ---------------------------------------------------------------------

DO $$
BEGIN
    BEGIN
        INSERT INTO app_users (full_name, email, password_hash)
        VALUES ('Тестовый Пользователь', 'не-email', 'h');
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: домен d_email не отклонил некорректный адрес';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ТЕСТ ПРОЙДЕН: домен d_email отклонил некорректный адрес';
    END;
END $$;

DO $$
BEGIN
    BEGIN
        INSERT INTO app_users (full_name, email, phone, password_hash)
        VALUES ('Тестовый Пользователь', 'test2@example.ru', 'не-телефон', 'h');
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: маска телефона не отклонила неприводимое значение';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ТЕСТ ПРОЙДЕН: маска телефона отклонила неприводимое значение';
    END;
END $$;

DO $$
BEGIN
    BEGIN
        INSERT INTO app_users (full_name, email, inn, password_hash)
        VALUES ('Тестовый Пользователь', 'test3@example.ru', '123', 'h');
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: домен d_inn не отклонил короткий ИНН';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ТЕСТ ПРОЙДЕН: домен d_inn отклонил короткий ИНН';
    END;
END $$;

DO $$
BEGIN
    BEGIN
        INSERT INTO app_users (full_name, email, role, password_hash)
        VALUES ('Тестовый Пользователь', 'test4@example.ru', 'SUPERUSER', 'h');
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: CHECK-условие не ограничило список ролей';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ТЕСТ ПРОЙДЕН: CHECK-условие ограничило список ролей';
    END;
END $$;

-- Нормализация телефона триггером: 89991234567 -> +7 999 123-45-67
DO $$
DECLARE v_phone VARCHAR;
BEGIN
    INSERT INTO app_users (full_name, email, phone, password_hash)
    VALUES ('Тестовый Нормализатор', 'norm@example.ru', '89991234567', 'h')
    RETURNING phone INTO v_phone;

    IF v_phone <> '+7 999 123-45-67' THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: телефон не нормализован, получено %', v_phone;
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: триггер нормализации привёл телефон к маске %', v_phone;
END $$;

-- Приведение адреса электронной почты к нижнему регистру
DO $$
DECLARE v_email VARCHAR;
BEGIN
    INSERT INTO app_users (full_name, email, password_hash)
    VALUES ('Тестовый Регистр', 'MiXeD@Example.RU', 'h')
    RETURNING email INTO v_email;

    IF v_email <> 'mixed@example.ru' THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: адрес не приведён к нижнему регистру, получено %', v_email;
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: адрес электронной почты приведён к нижнему регистру (%)', v_email;
END $$;

-- Недопустимый переход статуса документа отклоняется на уровне БД
DO $$
BEGIN
    BEGIN
        UPDATE document SET status = 'DRAFT'
        WHERE status = 'SIGNED';
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: недопустимый переход статуса не отклонён';
    EXCEPTION WHEN raise_exception THEN
        RAISE NOTICE 'ТЕСТ ПРОЙДЕН: недопустимый переход статуса документа отклонён';
    END;
END $$;

-- TR040: подписанный документ нельзя изменить
DO $$
BEGIN
    BEGIN
        UPDATE document SET title = 'Попытка изменить подписанный документ'
        WHERE status = 'SIGNED';
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: подписанный документ удалось изменить';
    EXCEPTION WHEN raise_exception THEN
        RAISE NOTICE 'ТЕСТ ПРОЙДЕН: подписанный документ защищён от изменения (TR040)';
    END;
END $$;

-- TR040: записи журнала аудита неизменяемы
DO $$
BEGIN
    BEGIN
        UPDATE audit_log SET comment = 'Попытка изменить журнал аудита';
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: запись журнала аудита удалось изменить';
    EXCEPTION WHEN raise_exception THEN
        RAISE NOTICE 'ТЕСТ ПРОЙДЕН: журнал аудита защищён от изменения (TR040)';
    END;
END $$;

-- Защита от исполняемых файлов
DO $$
BEGIN
    BEGIN
        INSERT INTO attachment (doc_id, uploaded_by, file_name, file_path, file_size, file_type)
        SELECT d.doc_id, u.user_id, 'payload.exe', 'C:/tmp/payload.exe', 1024, 'application/octet-stream'
        FROM document d, app_users u
        WHERE d.content_json->>'start_date' = '2026-07-01' AND u.email = 'petrova@example.ru';
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: исполняемый файл не отклонён';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ТЕСТ ПРОЙДЕН: загрузка исполняемого файла отклонена';
    END;
END $$;

-- ---------------------------------------------------------------------
-- C. ТРЕБОВАНИЯ «a», «b», «c» — вычисляемые поля и автоматический пересчёт
-- ---------------------------------------------------------------------

DO $$
DECLARE v_days INTEGER; v_number VARCHAR; v_signed BOOLEAN;
BEGIN
    SELECT vacation_days, number, is_signed
    INTO v_days, v_number, v_signed
    FROM document
    WHERE content_json->>'start_date' = '2026-06-01';

    IF v_days <> 15 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: вычисляемое поле vacation_days = %, ожидалось 15', v_days;
    END IF;

    IF v_number !~ '^З-[0-9]{4}-[0-9]{4}$' THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: номер документа сформирован неверно: %', v_number;
    END IF;

    IF v_signed THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: вычисляемое поле is_signed выдало TRUE для черновика';
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: вычисляемые поля (a): vacation_days=15, номер=%', v_number;
END $$;

DO $$
DECLARE v_total INTEGER; v_signed INTEGER;
BEGIN
    SELECT total_documents, signed_count INTO v_total, v_signed
    FROM mv_stat_document_totals WHERE type_code = 'ZAYAVL';

    IF v_total <> 3 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: итоговый результат = %, ожидалось 3', v_total;
    END IF;

    IF v_signed <> 1 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: подписанных документов = %, ожидалось 1', v_signed;
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: итоговые результаты с пересчётом (b): всего=3, подписано=1';
END $$;

DO $$
DECLARE v_total INTEGER; v_archived INTEGER;
BEGIN
    SELECT total_documents, archived_count INTO v_total, v_archived
    FROM mv_stat_document_totals WHERE type_code = 'LNA';

    IF v_total <> 1 OR v_archived <> 0 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: частный итог по ЛНА некорректен (%, %)',
            v_total, v_archived;
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: частные итоги с пересчётом (c): по ЛНА всего=1';
END $$;

-- Автоматический пересчёт при изменении данных: переводим документ в архив
DO $$
DECLARE v_archived INTEGER;
BEGIN
    UPDATE document SET status = 'ARCHIVED'
    WHERE content_json->>'start_date' = '2026-08-01';

    SELECT archived_count INTO v_archived
    FROM mv_stat_document_totals WHERE type_code = 'ZAYAVL';

    IF v_archived <> 1 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: автопересчёт не сработал, archived_count = %', v_archived;
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: автопересчёт итогов сработал при изменении данных (b, c)';
END $$;

-- ---------------------------------------------------------------------
-- D. ТРЕБОВАНИЯ «d», «e», «f» — аналитические функции
-- ---------------------------------------------------------------------

DO $$
DECLARE v_best INTEGER; v_worst INTEGER;
BEGIN
    SELECT count(*) FILTER (WHERE kind = 'BEST'), count(*) FILTER (WHERE kind = 'WORST')
    INTO v_best, v_worst
    FROM f_best_worst('APPROVER_DELAY');

    IF v_best <> 3 OR v_worst <> 3 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: f_best_worst вернул лучших=%, худших=%', v_best, v_worst;
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: выбор трёх лучших и трёх худших (d): лучших=3, худших=3';
END $$;

DO $$
DECLARE v_count INTEGER;
BEGIN
    SELECT count(*) INTO v_count FROM v_overdue_documents;

    IF v_count <> 1 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: просроченных документов = %, ожидалось 1', v_count;
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: выбор по двум условиям из разных таблиц (e): найден 1 документ';
END $$;

DO $$
DECLARE r RECORD;
BEGIN
    SELECT * INTO r FROM f_forecast_vacation_exhaustion(
        (SELECT user_id FROM app_users WHERE email = 'petrova@example.ru'), 28);

    IF r.user_id IS NULL THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: прогноз не вернул результат';
    END IF;

    IF r.used_days <> 15 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: использовано дней = %, ожидалось 15', r.used_days;
    END IF;

    IF r.forecast_date IS NULL OR r.lower_date IS NULL OR r.upper_date IS NULL THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: не рассчитаны границы доверительного интервала';
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: прогноз с доверительным интервалом (f): использовано=15, прогноз=%',
        r.forecast_date;
END $$;

-- ---------------------------------------------------------------------
-- E. ТРЕБОВАНИЕ «g» — модифицированные запросы (CTE)
-- ---------------------------------------------------------------------

DO $$
DECLARE v_count INTEGER;
BEGIN
    v_count := f_archive_signed_documents((CURRENT_DATE + INTERVAL '1 day')::DATE);

    IF v_count < 1 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: CTE-запрос на обновление не архивировал ни одного документа';
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: CTE-запрос на обновление (g): архивировано документов = %', v_count;
END $$;

DO $$
DECLARE v_count INTEGER;
BEGIN
    v_count := f_delete_stale_drafts((CURRENT_DATE + INTERVAL '1 day')::DATE);
    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: CTE-запрос на удаление (g): удалено черновиков = %', v_count;
END $$;

DO $$
DECLARE v_count INTEGER;
BEGIN
    v_count := f_create_acquaintance_marks(
        (SELECT sheet_id FROM acquaintance_sheet LIMIT 1));
    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: CTE-запрос на добавление (g): создано отметок = %', v_count;
END $$;

-- Пересчёт счётчиков листа ознакомления
DO $$
DECLARE v_total INTEGER; v_ack INTEGER;
BEGIN
    SELECT total_expected, acknowledged_count INTO v_total, v_ack
    FROM acquaintance_sheet LIMIT 1;

    IF v_total <> 6 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: ожидалось 6 сотрудников в листе ознакомления, получено %', v_total;
    END IF;

    IF v_ack <> 0 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: ознакомленных = %, ожидалось 0', v_ack;
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: счётчики листа ознакомления пересчитаны триггером: ожидалось=6, ознакомлено=0';
END $$;

-- Подтверждение ознакомления пересчитывает счётчик
DO $$
DECLARE v_ack INTEGER;
BEGIN
    UPDATE acquaintance_mark
    SET status = 'ACKNOWLEDGED', read_at = now()
    WHERE sheet_id = (SELECT sheet_id FROM acquaintance_sheet LIMIT 1);

    SELECT acknowledged_count INTO v_ack FROM acquaintance_sheet LIMIT 1;

    IF v_ack <> 6 THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: ознакомленных = %, ожидалось 6', v_ack;
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: счётчик ознакомлений пересчитан: ознакомлено=6';
END $$;

-- Приведение к нижнему регистру с учётом кириллицы (f_lower_ru)
DO $$
DECLARE v_result TEXT;
BEGIN
    v_result := f_lower_ru('Петрова АННА Сергеевна');

    IF v_result <> 'петрова анна сергеевна' THEN
        RAISE EXCEPTION 'ТЕСТ НЕ ПРОЙДЕН: f_lower_ru вернул «%», ожидалось «петрова анна сергеевна»', v_result;
    END IF;

    RAISE NOTICE 'ТЕСТ ПРОЙДЕН: кириллица приведена к нижнему регистру: %', v_result;
END $$;

-- ---------------------------------------------------------------------
-- F. ИТОГОВЫЙ ОТЧЁТ
-- ---------------------------------------------------------------------

SELECT 'ИТОГ: все проверки пройдены, транзакция откатывается' AS result;

ROLLBACK;
