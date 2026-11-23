-- =====================================================================
-- ИС КЭДО — установка базы данных одним файлом
--
-- Этот файл СОБИРАЕТСЯ автоматически скриптом sql/build-install.ps1
-- из модульных скриптов. Правьте модульные скрипты, затем пересобирайте:
--
--     powershell -ExecutionPolicy Bypass -File sql\build-install.ps1
--
-- Содержимое:
--   1) полный сброс схемы            — sql/00_reset.sql
--   2) домены, таблицы, индексы      — sql/01_schema.sql
--   3) триггеры, представления, функции — sql/02_objects.sql
--   4) отчёт о составе созданной схемы
--
-- Файл самодостаточный: его можно выполнить целиком одним действием
-- в Supabase (SQL Editor) или в PgAdmin, не соблюдая порядок запуска
-- отдельных скриптов. Повторное выполнение безопасно — схема каждый
-- раз пересоздаётся заново.
--
-- ВНИМАНИЕ: выполнение файла удаляет все ранее созданные объекты
--           и данные ИС КЭДО. Это штатный порядок развёртывания
--           учебного прототипа.
--
-- Порядок выполнения модульных скриптов (для документации):
--   sql/00_reset.sql      — сброс схемы
--   sql/01_schema.sql     — домены, таблицы, индексы
--   sql/02_objects.sql    — триггеры, представления, функции
--   sql/99_smoke_test.sql — проверочный сценарий (необязательно)
-- =====================================================================


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


-- ---------------------------------------------------------------------
-- 1. SCHEMA RESET
-- ---------------------------------------------------------------------

-- =====================================================================
-- ИС КЭДО — схема базы данных (шаг «Создание базы данных в СУБД»)
--
-- Источник истины по структуре БД. Соответствует:
--   • таблице 3.2 «Таблицы базы данных ИС КЭДО» (расчётно-пояснительная записка);
--   • разделу 3.7 «Проектирование структуры базы данных»;
--   • требованиям FR010–FR220 и TR030–TR040.
--
-- СУБД: PostgreSQL (Supabase / локальная БД через PgAdmin).
-- Версия: PostgreSQL 12 и выше (используются GENERATED-колонки, JSONB, CTE).
--
-- Порядок выполнения:
--   1) sql/00_reset.sql      — сброс (если схема уже создавалась)
--   2) sql/01_schema.sql      — этот файл
--   3) sql/02_objects.sql     — триггеры, представления, функции
--
-- Обозначения:
--   • таблицы именуются в нижнем регистре;
--   • «user» — зарезервированное слово PostgreSQL, поэтому таблица
--     пользователей называется app_users;
--   • значения перечислений хранятся строками и ограничены CHECK-условиями.
-- =====================================================================

SET client_encoding = 'UTF8';
SET search_path TO public;

-- ---------------------------------------------------------------------
-- 0. ПРОВЕРКА СООТВЕТСТВИЯ СХЕМЫ И САМОВОССТАНОВЛЕНИЕ
--
-- Схема полностью определяется этим скриптом. Если какая-либо из таблиц
-- уже существует, но её состав столбцов отличается от ожидаемого
-- (например, таблица создана автоматически Hibernate на раннем этапе
-- разработки), такая таблица не может быть использована: в ней нет
-- обязательных полей, а часть полей имеет неверный тип.
--
-- В этом случае все таблицы схемы пересоздаются. Таблицы с корректной
-- структурой и данными не затрагиваются.
--
-- После этого выполните:
--   sql/01_schema.sql
--   sql/02_objects.sql
-- ---------------------------------------------------------------------
DO $$
DECLARE
    v_expected JSONB := '{
        "organization":       ["org_id","name","inn","kpp","legal_address","is_active"],
        "department":         ["dept_id","org_id","parent_dept_id","manager_id","name","code","level","path","created_at","liquidated_at"],
        "position":           ["position_id","name"],
        "app_users":          ["user_id","dept_id","position_id","personnel_number","full_name","date_of_birth","inn","snils","phone","email","role","status","password_hash","created_at","last_login"],
        "user_settings":      ["user_id","notif_web_enabled","notif_email_enabled","notif_event_types","theme","language"],
        "delegation":         ["delegation_id","from_user_id","to_user_id","date_from","date_to","created_at"],
        "doc_type":           ["type_id","code","name","category","template_json","requires_approval","requires_signature","signature_type","requires_acquaintance","retention_years","is_active"],
        "document":           ["doc_id","type_id","author_id","base_doc_id","number","title","status","content_json","content_url","period_start","period_end","created_at","updated_at","vacation_days","is_signed","is_archived"],
        "route_rule":         ["rule_id","type_id","dept_id","initiator_position_id","approver_position_id","approver_dept_id","step_order","action","deadline_days"],
        "route_step":         ["step_id","doc_id","user_id","delegated_from","step_order","action","status","comment","decided_at","deadline","is_parallel","signature_data"],
        "attachment":         ["attach_id","doc_id","uploaded_by","file_name","file_path","file_size","file_type","uploaded_at"],
        "acquaintance_sheet": ["sheet_id","doc_id","created_by","audience_type","audience_ref","deadline","status","created_at","total_expected","acknowledged_count"],
        "acquaintance_mark":  ["mark_id","sheet_id","user_id","status","read_at","reminder_sent_at","signature_data","is_acknowledged"],
        "notification":       ["notif_id","user_id","doc_id","event_type","channel","body","created_at","read_at"],
        "audit_log":          ["audit_id","doc_id","user_id","action","comment","changed_fields","created_at"]
    }'::jsonb;
    v_table     TEXT;
    v_actual    TEXT[];
    v_planned   TEXT[];
    v_actual_s  TEXT;
    v_planned_s TEXT;
    v_rebuild   BOOLEAN := FALSE;
BEGIN
    FOR v_table IN SELECT jsonb_object_keys(v_expected) ORDER BY 1 LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname = v_table AND c.relkind = 'r'
        ) THEN
            CONTINUE;   -- таблицы ещё нет — она будет создана ниже
        END IF;

        SELECT array_agg(a.attname ORDER BY a.attname)
        INTO v_actual
        FROM pg_attribute a
        JOIN pg_class c ON c.oid = a.attrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relname = v_table
          AND a.attnum > 0 AND NOT a.attisdropped;

        v_planned := ARRAY(
            SELECT jsonb_array_elements_text(v_expected -> v_table) ORDER BY 1
        );

        SELECT string_agg(x, ',' ORDER BY x) INTO v_actual_s  FROM unnest(v_actual)  AS x;
        SELECT string_agg(x, ',' ORDER BY x) INTO v_planned_s FROM unnest(v_planned) AS x;

        IF v_actual_s IS DISTINCT FROM v_planned_s THEN
            RAISE NOTICE 'Таблица % имеет структуру, отличную от ожидаемой — схема будет пересоздана.', v_table;
            v_rebuild := TRUE;
        END IF;
    END LOOP;

    IF v_rebuild THEN
        RAISE NOTICE 'Обнаружена несовпадающая структура таблиц. Все таблицы схемы пересоздаются, данные в них будут удалены.';
        DROP TABLE IF EXISTS
            audit_log, notification, acquaintance_mark, acquaintance_sheet, attachment,
            route_step, route_rule, document, doc_type, delegation, user_settings,
            app_users, position, department, organization
            CASCADE;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 1. ДОМЕНЫ — защита от ошибок ручного ввода
--    (требование «условия на вводимые данные, маски, домены»)
-- ---------------------------------------------------------------------

-- Примечание: PostgreSQL не поддерживает «CREATE DOMAIN IF NOT EXISTS»,
-- поэтому каждый домен создаётся через блок DO — скрипт можно выполнять повторно.

-- Электронная почта
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'd_email') THEN
        CREATE DOMAIN d_email AS VARCHAR(254)
            CONSTRAINT d_email_pattern CHECK (VALUE ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$');
    END IF;
END $$;

-- ИНН — 12 цифр
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'd_inn') THEN
        CREATE DOMAIN d_inn AS VARCHAR(12)
            CONSTRAINT d_inn_pattern CHECK (VALUE ~ '^[0-9]{12}$');
    END IF;
END $$;

-- СНИЛС — 11 цифр, записанных без разделителей (00100100100)
-- или в принятой форме XXX-XXX-XXX XX
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'd_snils') THEN
        CREATE DOMAIN d_snils AS VARCHAR(14)
            CONSTRAINT d_snils_pattern
                CHECK (VALUE ~ '^([0-9]{11}|[0-9]{3}-[0-9]{3}-[0-9]{3} [0-9]{2})$');
    END IF;
END $$;

-- Телефон хранится в столбце app_users.phone с обычным CHECK-условием
-- (маска +7 XXX XXX-XX-XX), а не доменом: домен проверяется при приведении
-- значения к типу, то есть ДО срабатывания BEFORE-триггера, и нормализация
-- номера на уровне БД была бы невозможна. Подробнее — в комментарии
-- к столбцу app_users.phone.

-- Код вида документа — латинские буквы, цифры и подчёркивание
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'd_doc_code') THEN
        CREATE DOMAIN d_doc_code AS VARCHAR(20)
            CONSTRAINT d_doc_code_pattern CHECK (VALUE ~ '^[A-Z0-9_]{2,20}$');
    END IF;
END $$;

-- Табельный номер — например «ИУ3-00001», «000123» или «ИУ3-53Б»
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'd_personnel_number') THEN
        CREATE DOMAIN d_personnel_number AS VARCHAR(20)
            CONSTRAINT d_personnel_number_pattern
                CHECK (VALUE ~ '^[А-ЯЁA-Z0-9]{1,8}(-[0-9А-ЯЁA-Z]{1,8})?$');
    END IF;
END $$;

-- Домен d_phone больше не используется: маска телефона обеспечивается
-- CHECK-условием столбца app_users.phone (см. комментарий выше).
-- Удаляем его, если он остался от предыдущей версии скрипта.
DROP DOMAIN IF EXISTS d_phone;

-- ФИО — не пусто, без служебных символов
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'd_full_name') THEN
        CREATE DOMAIN d_full_name AS VARCHAR(150)
            CONSTRAINT d_full_name_pattern
                CHECK (VALUE ~ '^[А-ЯЁа-яёA-Za-z][А-ЯЁа-яёA-Za-z .,()\-]{1,149}$');
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 2. СПРАВОЧНИКИ: ОРГАНИЗАЦИЯ, ПОДРАЗДЕЛЕНИЕ, ДОЛЖНОСТЬ
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS organization (
    org_id         INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    inn            d_inn,
    kpp            VARCHAR(10),
    legal_address  VARCHAR(500),
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_organization_kpp CHECK (kpp IS NULL OR kpp ~ '^[0-9]{9}$')
);

COMMENT ON TABLE  organization                IS 'Организация — юридическое лицо, в рамках которого ведётся кадровый документооборот';
COMMENT ON COLUMN organization.inn            IS 'ИНН организации';
COMMENT ON COLUMN organization.kpp            IS 'КПП организации';
COMMENT ON COLUMN organization.is_active      IS 'Признак действующей организации';

CREATE TABLE IF NOT EXISTS department (
    dept_id        INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    org_id         INTEGER NOT NULL REFERENCES organization (org_id),
    parent_dept_id INTEGER REFERENCES department (dept_id),
    manager_id     INTEGER,
    name           VARCHAR(200) NOT NULL,
    code           VARCHAR(50)  NOT NULL,
    level          INTEGER NOT NULL DEFAULT 0,
    path           VARCHAR(500) NOT NULL DEFAULT '',
    created_at     TIMESTAMP   NOT NULL DEFAULT now(),
    liquidated_at  TIMESTAMP,
    CONSTRAINT uq_department_code UNIQUE (org_id, code),
    CONSTRAINT ck_department_level CHECK (level >= 0),
    CONSTRAINT ck_department_not_self_parent CHECK (parent_dept_id IS NULL OR parent_dept_id <> dept_id)
);

COMMENT ON TABLE  department                IS 'Подразделение — структурная единица организации (департамент, отдел, филиал)';
COMMENT ON COLUMN department.parent_dept_id IS 'Родительское подразделение (иерархия подразделений)';
COMMENT ON COLUMN department.manager_id     IS 'Руководитель подразделения (ссылка добавляется после создания app_users)';
COMMENT ON COLUMN department.level         IS 'Уровень в иерархии; 0 — верхний уровень. Заполняется триггером';
COMMENT ON COLUMN department.path          IS 'Путь в иерархии вида /1/4/9/. Заполняется триггером';

CREATE TABLE IF NOT EXISTS position (
    position_id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    CONSTRAINT uq_position_name UNIQUE (name)
);

COMMENT ON TABLE  position          IS 'Справочник должностей: по нему строятся правила маршрутизации';
COMMENT ON COLUMN position.name     IS 'Наименование должности';

-- ---------------------------------------------------------------------
-- 3. ПОЛЬЗОВАТЕЛИ, НАСТРОЙКИ, ДЕЛЕГИРОВАНИЕ
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS app_users (
    user_id           INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    dept_id           INTEGER REFERENCES department (dept_id),
    position_id       INTEGER REFERENCES position (position_id),
    personnel_number  d_personnel_number,
    full_name         d_full_name NOT NULL,
    date_of_birth     DATE,
    inn               d_inn,
    snils             d_snils,
    phone             VARCHAR(20),
    email             d_email NOT NULL,
    role              VARCHAR(20) NOT NULL DEFAULT 'EMPLOYEE',
    status            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    password_hash     VARCHAR(100) NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    last_login        TIMESTAMP,
    CONSTRAINT uq_users_email              UNIQUE (email),
    CONSTRAINT uq_users_inn                UNIQUE (inn),
    CONSTRAINT uq_users_snils              UNIQUE (snils),
    CONSTRAINT uq_users_phone              UNIQUE (phone),
    CONSTRAINT uq_users_personnel_number   UNIQUE (personnel_number),
    CONSTRAINT ck_users_role               CHECK (role IN ('EMPLOYEE', 'MANAGER', 'HR', 'ADMIN')),
    CONSTRAINT ck_users_status             CHECK (status IN ('CREATED', 'ACTIVE', 'BLOCKED', 'DELETED')),
    -- Маска телефона. Проверяется после BEFORE-триггера trg_normalize_user,
    -- который приводит номер к каноническому виду (см. комментарий к d_phone).
    CONSTRAINT ck_users_phone              CHECK (phone IS NULL OR phone ~ '^\+7 [0-9]{3} [0-9]{3}-[0-9]{2}-[0-9]{2}$')
);

COMMENT ON TABLE  app_users                   IS 'Учётная запись пользователя ИС КЭДО';
COMMENT ON COLUMN app_users.full_name         IS 'Фамилия, имя, отчество';
COMMENT ON COLUMN app_users.personnel_number  IS 'Табельный номер';
COMMENT ON COLUMN app_users.role              IS 'Роль: EMPLOYEE — сотрудник, MANAGER — руководитель, HR — сотрудник ОК, ADMIN — администратор';
COMMENT ON COLUMN app_users.status            IS 'Статус учётной записи: CREATED, ACTIVE, BLOCKED, DELETED';
COMMENT ON COLUMN app_users.password_hash     IS 'Хэш пароля (BCrypt). Открытый пароль в базе не хранится (TR030)';
COMMENT ON COLUMN app_users.last_login        IS 'Дата и время последнего входа';

CREATE TABLE IF NOT EXISTS user_settings (
    user_id              INTEGER PRIMARY KEY REFERENCES app_users (user_id) ON DELETE CASCADE,
    notif_web_enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    notif_email_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    notif_event_types    VARCHAR(1000) NOT NULL DEFAULT '',
    theme                VARCHAR(20) NOT NULL DEFAULT 'light',
    language             VARCHAR(10) NOT NULL DEFAULT 'ru',
    CONSTRAINT ck_user_settings_theme    CHECK (theme IN ('light', 'dark', 'auto')),
    CONSTRAINT ck_user_settings_language CHECK (language IN ('ru', 'en'))
);

-- Upgrade existing databases: CREATE TABLE IF NOT EXISTS does not add new columns.
ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS notif_event_types VARCHAR(1000) NOT NULL DEFAULT '';

COMMENT ON TABLE  user_settings IS 'Персональные настройки пользователя. Связь с app_users — 1 : 1';

CREATE TABLE IF NOT EXISTS delegation (
    delegation_id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    from_user_id  INTEGER NOT NULL REFERENCES app_users (user_id),
    to_user_id    INTEGER NOT NULL REFERENCES app_users (user_id),
    date_from     DATE NOT NULL,
    date_to       DATE NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_delegation_not_self  CHECK (from_user_id <> to_user_id),
    CONSTRAINT ck_delegation_period    CHECK (date_from <= date_to)
);

COMMENT ON TABLE  delegation           IS 'Делегирование полномочий: документы, адресованные from_user_id, направляются to_user_id';
COMMENT ON COLUMN delegation.date_from IS 'Начало срока делегирования';
COMMENT ON COLUMN delegation.date_to   IS 'Окончание срока делегирования';

-- Руководитель подразделения — циклическая ссылка, добавляется после app_users
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_department_manager'
    ) THEN
        ALTER TABLE department
            ADD CONSTRAINT fk_department_manager
            FOREIGN KEY (manager_id) REFERENCES app_users (user_id);
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 4. СПРАВОЧНИК ВИДОВ ДОКУМЕНТОВ И САМИ ДОКУМЕНТЫ
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS doc_type (
    type_id                INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code                   d_doc_code  NOT NULL,
    name                   VARCHAR(200) NOT NULL,
    category               VARCHAR(50)  NOT NULL,
    template_json          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    requires_approval      BOOLEAN      NOT NULL DEFAULT TRUE,
    requires_signature     BOOLEAN      NOT NULL DEFAULT FALSE,
    signature_type         VARCHAR(30),
    requires_acquaintance  BOOLEAN      NOT NULL DEFAULT FALSE,
    retention_years        INTEGER      NOT NULL DEFAULT 5,
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_doc_type_code         UNIQUE (code),
    CONSTRAINT ck_doc_type_category     CHECK (category IN ('APPLICATION', 'ORDER', 'LNA', 'MEMO', 'OTHER')),
    CONSTRAINT ck_doc_type_signature    CHECK (signature_type IS NULL OR signature_type IN ('NONE', 'SIMPLE_EP', 'QUALIFIED_EP')),
    CONSTRAINT ck_doc_type_retention    CHECK (retention_years >= 0)
);

COMMENT ON TABLE  doc_type                      IS 'Справочник видов документов: форма бланка, обязательные реквизиты, порядок визирования, срок хранения';
COMMENT ON COLUMN doc_type.template_json         IS 'Описание полей формы вида документа в формате JSON. Значения полей проверяются ValidationService по этому описанию';
COMMENT ON COLUMN doc_type.requires_approval     IS 'Признак того, что документ проходит согласование';
COMMENT ON COLUMN doc_type.requires_signature    IS 'Признак того, что документ подписывается';
COMMENT ON COLUMN doc_type.requires_acquaintance IS 'Признак того, что по документу формируется лист ознакомления';
COMMENT ON COLUMN doc_type.retention_years       IS 'Срок хранения документа в архиве, лет';

CREATE TABLE IF NOT EXISTS document (
    doc_id      INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    type_id     INTEGER NOT NULL REFERENCES doc_type (type_id),
    author_id   INTEGER NOT NULL REFERENCES app_users (user_id),
    base_doc_id INTEGER REFERENCES document (doc_id),
    number      VARCHAR(30),
    title       VARCHAR(300) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    content_json JSONB       NOT NULL DEFAULT '{}'::jsonb,
    content_url VARCHAR(500),
    period_start DATE,
    period_end   DATE,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),
    -- Вычисляемые поля (требование «а»): используются в запросах и отчётах
    vacation_days INTEGER GENERATED ALWAYS AS (
        CASE
            WHEN period_start IS NOT NULL AND period_end IS NOT NULL
            THEN period_end - period_start + 1
            ELSE NULL
        END
    ) STORED,
    is_signed  BOOLEAN GENERATED ALWAYS AS (status IN ('SIGNED', 'ARCHIVED')) STORED,
    is_archived BOOLEAN GENERATED ALWAYS AS (status = 'ARCHIVED') STORED,
    CONSTRAINT uq_document_number      UNIQUE (number),
    CONSTRAINT ck_document_status      CHECK (status IN ('DRAFT', 'IN_APPROVAL', 'REWORK', 'APPROVED',
                                                           'SIGNED', 'REJECTED', 'RECALLED', 'ARCHIVED')),
    CONSTRAINT ck_document_title       CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_document_period      CHECK (period_start IS NULL OR period_end IS NULL OR period_end >= period_start),
    -- Даты периода дополнительно проверяются в JSON — защита от ошибок ввода
    CONSTRAINT ck_document_json_dates   CHECK ((content_json->>'start_date') IS NULL
                                                 OR (content_json->>'start_date') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
    CONSTRAINT ck_document_json_end     CHECK ((content_json->>'end_date') IS NULL
                                                 OR (content_json->>'end_date') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')
);

COMMENT ON TABLE  document              IS 'Кадровый документ: заявление, приказ, ЛНА и прочие';
COMMENT ON COLUMN document.base_doc_id IS 'Документ-основание (для приказа — заявление, на основании которого он подготовлен)';
COMMENT ON COLUMN document.number      IS 'Регистрационный номер. Присваивается автоматически триггером по префиксу вида документа';
COMMENT ON COLUMN document.status      IS 'Статус: DRAFT, IN_APPROVAL, REWORK, APPROVED, SIGNED, REJECTED, RECALLED, ARCHIVED';
COMMENT ON COLUMN document.content_json IS 'Значения полей формы документа в формате JSON. Набор полей зависит от вида документа';
COMMENT ON COLUMN document.period_start IS 'Начало периода (даты отпуска и т. п.). Дублирует значения полей формы, чтобы по ним можно было искать обычными столбцами';
COMMENT ON COLUMN document.period_end   IS 'Окончание периода';
COMMENT ON COLUMN document.vacation_days IS 'ВЫЧИСЛЯЕМОЕ ПОЛЕ: число календарных дней периода (period_end - period_start + 1)';
COMMENT ON COLUMN document.is_signed   IS 'ВЫЧИСЛЯЕМОЕ ПОЛЕ: признак подписанного документа';
COMMENT ON COLUMN document.is_archived IS 'ВЫЧИСЛЯЕМОЕ ПОЛЕ: признак документа, переведённого в архив';

-- ---------------------------------------------------------------------
-- 5. МАРШРУТИЗАЦИЯ
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS route_rule (
    rule_id                INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    type_id                INTEGER NOT NULL REFERENCES doc_type (type_id),
    dept_id                INTEGER REFERENCES department (dept_id),
    initiator_position_id  INTEGER REFERENCES position (position_id),
    approver_position_id   INTEGER REFERENCES position (position_id),
    approver_dept_id       INTEGER REFERENCES department (dept_id),
    step_order             INTEGER NOT NULL DEFAULT 1,
    action                 VARCHAR(20) NOT NULL DEFAULT 'APPROVE',
    deadline_days          INTEGER NOT NULL DEFAULT 3,
    CONSTRAINT ck_route_rule_order     CHECK (step_order > 0),
    CONSTRAINT ck_route_rule_deadline  CHECK (deadline_days > 0),
    CONSTRAINT ck_route_rule_action    CHECK (action IN ('APPROVE', 'SIGN', 'REVIEW'))
);

COMMENT ON TABLE  route_rule                       IS 'Правило построения маршрута согласования (FR050)';
COMMENT ON COLUMN route_rule.dept_id                IS 'Подразделение инициатора; NULL — «для всех»';
COMMENT ON COLUMN route_rule.initiator_position_id  IS 'Должность инициатора; NULL — «для всех»';
COMMENT ON COLUMN route_rule.approver_position_id   IS 'Должность согласующего';
COMMENT ON COLUMN route_rule.approver_dept_id       IS 'Подразделение согласующего';
COMMENT ON COLUMN route_rule.deadline_days          IS 'Срок этапа в рабочих днях';

CREATE TABLE IF NOT EXISTS route_step (
    step_id        INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    doc_id         INTEGER NOT NULL REFERENCES document (doc_id) ON DELETE CASCADE,
    user_id        INTEGER NOT NULL REFERENCES app_users (user_id),
    delegated_from INTEGER REFERENCES app_users (user_id),
    step_order     INTEGER NOT NULL,
    action         VARCHAR(20) NOT NULL DEFAULT 'APPROVE',
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    comment        VARCHAR(1000),
    decided_at     TIMESTAMP,
    deadline       TIMESTAMP,
    is_parallel    BOOLEAN NOT NULL DEFAULT FALSE,
    signature_data JSONB,
    CONSTRAINT ck_route_step_order   CHECK (step_order > 0),
    CONSTRAINT ck_route_step_action  CHECK (action IN ('APPROVE', 'SIGN', 'REVIEW')),
    CONSTRAINT ck_route_step_status  CHECK (status IN ('PENDING', 'IN_PROGRESS', 'APPROVED', 'REJECTED',
                                                        'RETURNED', 'DELEGATED', 'SKIPPED'))
);

COMMENT ON TABLE  route_step                 IS 'Этап маршрута согласования документа';
COMMENT ON COLUMN route_step.user_id         IS 'Согласующий, которому назначен этап';
COMMENT ON COLUMN route_step.delegated_from  IS 'Пользователь, от которого этап был делегирован (FR070, UC060)';
COMMENT ON COLUMN route_step.status         IS 'Статус этапа';
COMMENT ON COLUMN route_step.deadline       IS 'Срок исполнения этапа';
COMMENT ON COLUMN route_step.is_parallel    IS 'Признак параллельного этапа';
COMMENT ON COLUMN route_step.signature_data IS 'Данные простой электронной подписи (код подтверждения, дата)';

-- ---------------------------------------------------------------------
-- 6. ВЛОЖЕНИЯ
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS attachment (
    attach_id    INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    doc_id       INTEGER NOT NULL REFERENCES document (doc_id) ON DELETE CASCADE,
    uploaded_by  INTEGER NOT NULL REFERENCES app_users (user_id),
    file_name    VARCHAR(255) NOT NULL,
    file_path    VARCHAR(500) NOT NULL,
    file_size    INTEGER NOT NULL,
    file_type    VARCHAR(120),
    uploaded_at  TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT ck_attachment_size   CHECK (file_size > 0 AND file_size <= 26214400),
    CONSTRAINT ck_attachment_type   CHECK (lower(file_name) !~ '\.(exe|bat|cmd|com|scr|msi|jar|js|vbs|ps1)$')
);

COMMENT ON TABLE  attachment            IS 'Вложение к кадровому документу';
COMMENT ON COLUMN attachment.file_size IS 'Размер файла в байтах. Не более 25 МБ на файл (FR220)';
COMMENT ON COLUMN attachment.file_path IS 'Путь к файлу в каталоге вложений';

-- ---------------------------------------------------------------------
-- 7. ЛИСТЫ ОЗНАКОМЛЕНИЯ
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS acquaintance_sheet (
    sheet_id           INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    doc_id             INTEGER NOT NULL REFERENCES document (doc_id) ON DELETE CASCADE,
    created_by         INTEGER NOT NULL REFERENCES app_users (user_id),
    audience_type      VARCHAR(20) NOT NULL,
    audience_ref       VARCHAR(200),
    deadline           DATE,
    status             VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    total_expected     INTEGER NOT NULL DEFAULT 0,
    acknowledged_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_sheet_audience  CHECK (audience_type IN ('ALL', 'DEPARTMENT', 'POSITION', 'LIST')),
    CONSTRAINT ck_sheet_status    CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_sheet_counters  CHECK (total_expected >= 0 AND acknowledged_count >= 0
                                         AND acknowledged_count <= total_expected)
);

COMMENT ON TABLE  acquaintance_sheet              IS 'Лист ознакомления с ЛНА или приказом (FR110)';
COMMENT ON COLUMN acquaintance_sheet.audience_type IS 'Аудитория: ALL — вся организация, DEPARTMENT — подразделение, POSITION — должность, LIST — перечень сотрудников';
COMMENT ON COLUMN acquaintance_sheet.deadline      IS 'Срок ознакомления';
COMMENT ON COLUMN acquaintance_sheet.total_expected     IS 'Пересчитывается триггером: сколько сотрудников должно ознакомиться';
COMMENT ON COLUMN acquaintance_sheet.acknowledged_count IS 'Пересчитывается триггером: сколько уже ознакомились';

CREATE TABLE IF NOT EXISTS acquaintance_mark (
    mark_id          INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    sheet_id         INTEGER NOT NULL REFERENCES acquaintance_sheet (sheet_id) ON DELETE CASCADE,
    user_id          INTEGER NOT NULL REFERENCES app_users (user_id),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    read_at          TIMESTAMP,
    reminder_sent_at TIMESTAMP,
    signature_data   JSONB,
    is_acknowledged  BOOLEAN GENERATED ALWAYS AS (status = 'ACKNOWLEDGED') STORED,
    CONSTRAINT uq_mark_sheet_user  UNIQUE (sheet_id, user_id),
    CONSTRAINT ck_mark_status      CHECK (status IN ('PENDING', 'ACKNOWLEDGED'))
);

COMMENT ON TABLE  acquaintance_mark             IS 'Отметка об ознакомлении: одна на сотрудника в листе';
COMMENT ON COLUMN acquaintance_mark.read_at     IS 'Дата и время подтверждения ознакомления';
COMMENT ON COLUMN acquaintance_mark.is_acknowledged IS 'ВЫЧИСЛЯЕМОЕ ПОЛЕ: признак подтверждённого ознакомления';

-- ---------------------------------------------------------------------
-- 8. УВЕДОМЛЕНИЯ И ЖУРНАЛ АУДИТА
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS notification (
    notif_id   INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES app_users (user_id) ON DELETE CASCADE,
    doc_id     INTEGER REFERENCES document (doc_id) ON DELETE CASCADE,
    event_type VARCHAR(40) NOT NULL,
    channel    VARCHAR(10) NOT NULL DEFAULT 'WEB',
    body       VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    read_at    TIMESTAMP,
    CONSTRAINT ck_notification_channel CHECK (channel IN ('WEB', 'EMAIL', 'SMS'))
);

COMMENT ON TABLE  notification            IS 'Уведомление пользователя о событии (FR130, FR135)';
COMMENT ON COLUMN notification.event_type IS 'Тип события: DOCUMENT_ARRIVED, DOCUMENT_APPROVED, DOCUMENT_SIGNED, DOCUMENT_REJECTED, DOCUMENT_RETURNED, DEADLINE_NEAR, DEADLINE_EXPIRED, ACQUAINTANCE_REQUIRED, ACQUAINTANCE_REMINDER, COMMENT_ADDED';
COMMENT ON COLUMN notification.read_at    IS 'Время прочтения. Пусто — уведомление не прочитано';

CREATE TABLE IF NOT EXISTS audit_log (
    audit_id       INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    doc_id         INTEGER REFERENCES document (doc_id),
    user_id        INTEGER REFERENCES app_users (user_id),
    action         VARCHAR(40) NOT NULL,
    comment        VARCHAR(1000),
    changed_fields JSONB,
    created_at     TIMESTAMP NOT NULL DEFAULT now()
);

COMMENT ON TABLE  audit_log             IS 'Журнал аудита: каждое действие над документом. Записи только добавляются (TR040)';
COMMENT ON COLUMN audit_log.action     IS 'Действие: CREATED, SUBMITTED, APPROVED, REJECTED, RETURNED, SIGNED, DELEGATED, RECALLED, ARCHIVED, ACKNOWLEDGED, COMMENT, IMPORTED, EXPORTED';
COMMENT ON COLUMN audit_log.changed_fields IS 'Изменённые поля документа в формате JSON';

-- ---------------------------------------------------------------------
-- 9. ИНДЕКСЫ
-- ---------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS ix_users_dept        ON app_users (dept_id);
CREATE INDEX IF NOT EXISTS ix_users_role        ON app_users (role);
CREATE INDEX IF NOT EXISTS ix_users_status      ON app_users (status);
CREATE INDEX IF NOT EXISTS ix_department_parent ON department (parent_dept_id);
CREATE INDEX IF NOT EXISTS ix_department_org    ON department (org_id);
CREATE INDEX IF NOT EXISTS ix_document_author   ON document (author_id);
CREATE INDEX IF NOT EXISTS ix_document_type     ON document (type_id);
CREATE INDEX IF NOT EXISTS ix_document_status   ON document (status);
CREATE INDEX IF NOT EXISTS ix_document_created  ON document (created_at);
CREATE INDEX IF NOT EXISTS ix_document_base     ON document (base_doc_id);
CREATE INDEX IF NOT EXISTS ix_step_doc          ON route_step (doc_id);
CREATE INDEX IF NOT EXISTS ix_step_user         ON route_step (user_id, status);
CREATE INDEX IF NOT EXISTS ix_step_deadline     ON route_step (deadline) WHERE status IN ('PENDING', 'IN_PROGRESS');
CREATE INDEX IF NOT EXISTS ix_rule_type         ON route_rule (type_id);
CREATE INDEX IF NOT EXISTS ix_attachment_doc    ON attachment (doc_id);
CREATE INDEX IF NOT EXISTS ix_mark_sheet        ON acquaintance_mark (sheet_id);
CREATE INDEX IF NOT EXISTS ix_mark_user         ON acquaintance_mark (user_id, status);
CREATE INDEX IF NOT EXISTS ix_notif_user        ON notification (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_notif_unread      ON notification (user_id) WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_audit_doc         ON audit_log (doc_id, created_at);
CREATE INDEX IF NOT EXISTS ix_audit_user        ON audit_log (user_id, created_at);

-- ---------------------------------------------------------------------
-- 10. КОММЕНТАРИИ ДЛЯ СПЕЦИФИКАЦИИ ТАБЛИЦ (раздел 3.7 записки)
-- ---------------------------------------------------------------------

COMMENT ON TABLE app_users             IS 'Уровень данных. Один человек — одна учётная запись. Уникальны ИНН, СНИЛС, email, телефон, табельный номер';
COMMENT ON TABLE position              IS 'Уровень данных. Справочник должностей';
COMMENT ON TABLE document              IS 'Уровень данных. Центральная сущность системы';
COMMENT ON TABLE doc_type              IS 'Уровень данных. Справочник видов документов';
COMMENT ON TABLE route_rule            IS 'Уровень данных. Правила построения маршрутов согласования';
COMMENT ON TABLE route_step            IS 'Уровень данных. Этапы маршрута согласования';
COMMENT ON TABLE attachment            IS 'Уровень данных. Вложения к документам';
COMMENT ON TABLE acquaintance_sheet    IS 'Уровень данных. Листы ознакомления';
COMMENT ON TABLE acquaintance_mark     IS 'Уровень данных. Отметки об ознакомлении';
COMMENT ON TABLE notification          IS 'Уровень данных. Уведомления пользователей';
COMMENT ON TABLE audit_log             IS 'Уровень данных. Журнал аудита действий над документами';
COMMENT ON TABLE delegation            IS 'Уровень данных. Делегирование полномочий';
COMMENT ON TABLE user_settings         IS 'Уровень данных. Персональные настройки пользователя';
COMMENT ON TABLE department            IS 'Уровень данных. Дерево подразделений организации';
COMMENT ON TABLE organization          IS 'Уровень данных. Организации системы';


-- ---------------------------------------------------------------------
-- 2. DOMAINS, TABLES, INDEXES
-- ---------------------------------------------------------------------

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

-- Приведение строки к нижнему регистру с учётом кириллицы.
--
-- Функция lower() сервера работает с кириллицей только при наличии
-- соответствующей локали (например, ICU или en_US.UTF-8); в локали C
-- она преобразует лишь латинские буквы. Чтобы поиск по ФИО работал
-- одинаково на любом сервере, кириллица приводится к нижнему регистру
-- таблицей замен translate(), а латиница — функцией lower().
CREATE OR REPLACE FUNCTION f_lower_ru(p_text TEXT)
RETURNS TEXT
LANGUAGE sql IMMUTABLE AS $$
    SELECT lower(translate(p_text,
        'АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ',
        'абвгдеёжзийклмнопрстуфхцчшщъыьэюя'));
$$;

COMMENT ON FUNCTION f_lower_ru(TEXT) IS 'Приводит строку к нижнему регистру с учётом кириллицы независимо от локали сервера';

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


-- ---------------------------------------------------------------------
-- 3. TRIGGERS, VIEWS, FUNCTIONS
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- ОТЧЁТ О СОСТАВЕ СОЗДАННОЙ СХЕМЫ
-- ---------------------------------------------------------------------

SELECT 'Таблицы'                          AS "Объект", count(*)::text AS "Количество"
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
UNION ALL
SELECT 'Домены', count(*)::text FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = 'public' AND t.typtype = 'd'
UNION ALL
SELECT 'Представления', count(*)::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'v'
UNION ALL
SELECT 'Материализованные представления', count(*)::text
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'm'
UNION ALL
SELECT 'Триггеры', count(*)::text
FROM pg_trigger t
JOIN pg_class c ON c.oid = t.tgrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE NOT t.tgisinternal AND n.nspname = 'public'
UNION ALL
SELECT 'Функции', count(*)::text FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND left(p.proname, 2) = 'f_'
ORDER BY 1;

SELECT c.relname AS "Таблица", count(a.attname) AS "Столбцов"
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
WHERE n.nspname = 'public' AND c.relkind = 'r'
GROUP BY c.relname
ORDER BY c.relname;

SELECT 'Схема ИС КЭДО создана. Демонстрационные учётные записи создаются при первом запуске приложения.' AS "Статус";

