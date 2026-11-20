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
        "user_settings":      ["user_id","notif_web_enabled","notif_email_enabled","theme","language"],
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
    theme                VARCHAR(20) NOT NULL DEFAULT 'light',
    language             VARCHAR(10) NOT NULL DEFAULT 'ru',
    CONSTRAINT ck_user_settings_theme    CHECK (theme IN ('light', 'dark', 'auto')),
    CONSTRAINT ck_user_settings_language CHECK (language IN ('ru', 'en'))
);

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
