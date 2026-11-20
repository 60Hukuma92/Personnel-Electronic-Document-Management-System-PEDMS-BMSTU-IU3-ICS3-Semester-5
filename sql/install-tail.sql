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
