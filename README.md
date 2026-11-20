# ИС КЭДО

Учебная информационная система кадрового электронного документооборота (ИС КЭДО) для организации и сопровождения кадровых документов.

## Возможности

- аутентификация и разграничение прав для ролей `EMPLOYEE`, `MANAGER`, `HR`, `ADMIN`;
- создание, редактирование, отзыв, согласование, отклонение и подписание документов;
- ручной и правиловый маршрут согласования с делегированием;
- изготовление проектов приказов из заявлений и пакетный импорт заказов;
- ознакомление с локальными нормативными актами и простая ЭП кодом подтверждения;
- уведомления, напоминания и эскалация просроченных этапов;
- журнал аудита и архивирование подписанных документов;
- управление пользователями, импорт CSV/XLSX, обезличивание учётных записей;
- отчёты и аналитика с выгрузкой CSV/XLSX;
- защита БД доменами, масками, `CHECK`-ограничениями, триггерами и представлениями.

## Технологии

- Java 17 / Kotlin 1.9.23;
- Spring Boot 3.2.5, Spring MVC, Spring Security, Spring Data JPA;
- Thymeleaf и Bootstrap;
- Gradle 8.5;
- PostgreSQL 12+ или Supabase;
- JUnit 5 и H2 для локальных тестов.

## Требования

- JDK 17 или новее;
- PostgreSQL или проект Supabase;
- для запуска тестов — доступ к Gradle и локальному кэшу зависимостей.

## Сборка

Из корня проекта:

```powershell
.\gradlew.bat clean test
.\gradlew.bat bootJar
```

Исполняемый JAR находится в `build/libs/pedms-0.0.1-SNAPSHOT.jar`.

## Настройка Supabase или PostgreSQL

Рекомендуется хранить реквизиты в переменных окружения, а не в исходном коде.

```powershell
$env:DB_URL = "jdbc:postgresql://<host>:5432/postgres"
$env:DB_USERNAME = "<пользователь>"
$env:DB_PASSWORD = "<пароль>"
```

Создайте тестовый или рабочий проект в Supabase и выполните в SQL Editor:

1. `sql/00_reset.sql` — только для очистки старой схемы;
2. `sql/01_schema.sql` — таблицы и домены;
3. `sql/02_objects.sql` — триггеры, представления и функции;
4. при необходимости `sql/99_smoke_test.sql` — проверка схемы.

Для локальной демонстрации можно скопировать `src/main/resources/application.properties.example` рядом с `src/main/resources/application.properties` и задать переменные. Production-конфигурация использует `spring.jpa.hibernate.ddl-auto=none`; Hibernate не создает и не изменяет таблицы.

## Запуск

```powershell
$env:DB_URL = "jdbc:postgresql://<host>:5432/postgres"
$env:DB_USERNAME = "<пользователь>"
$env:DB_PASSWORD = "<пароль>"
.\gradlew.bat bootRun
```

Приложение доступно по адресу `http://localhost:8080`.

При первом запуске `DataSeeder` наполняет справочники и демонстрационных пользователей. Параметр `app.demo-mode=true` в примере конфигурации отображает демонстрационные учётные записи на странице входа.

## Тестирование

Модульные тесты JUnit 5 выполняются без поднятия рабочей БД:

```powershell
.\gradlew.bat test
```

Интеграционный тест репозиториев использует H2 в режиме совместимости с PostgreSQL. Он проверяет связи между организациями, подразделениями и пользователями.

Для проверки реального PostgreSQL задайте отдельные переменные тестовой БД и запустите полный набор:

```powershell
$env:PEDMS_TEST_DB_URL = "jdbc:postgresql://<host>:5432/<test_database>"
$env:PEDMS_TEST_DB_USERNAME = "<test_user>"
$env:PEDMS_TEST_DB_PASSWORD = "<test_password>"
.\gradlew.bat test
```

Если переменная `PEDMS_TEST_DB_URL` не задана, тест реального PostgreSQL пропускается. Рабочая конфигурация приложения при этом не меняется.

## Структура проекта

```text
src/main/kotlin/bmstu/iu3/moiseev/pedms/
  config/      настройки безопасности, сидов и проверки схемы
  domain/      перечисления статусов и событий
  dto/         формы и DTO
  entity/      JPA-сущности
  repository/  репозитории Spring Data JPA
  service/     прикладная бизнес-логика
  web/         Spring MVC контроллеры
src/main/resources/
  templates/   Thymeleaf-шаблоны
  static/      клиентские ресурсы
sql/           скрипты установки, сброса и smoke-тестирования PostgreSQL
docs/          план, архитектура, база данных, протокол и описание PR
src/test/      модульные и интеграционные тесты JUnit 5
```

## Документация

- [`docs/PLAN.md`](docs/PLAN.md) — план и требования проекта;
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — архитектура;
- [`docs/DATABASE.md`](docs/DATABASE.md) — структура базы данных;
- [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md) — руководство для ролей;
- [`docs/TEST_PROTOCOL.md`](docs/TEST_PROTOCOL.md) — протокол тестирования;
- [`docs/PR-testing-documentation.md`](docs/PR-testing-documentation.md) — описание PR этапа.
