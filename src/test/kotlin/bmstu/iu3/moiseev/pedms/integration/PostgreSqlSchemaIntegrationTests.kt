package bmstu.iu3.moiseev.pedms.integration

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/**
 * Проверяет реальное подключение к PostgreSQL, если задана тестовая БД.
 * В обычной локальной сборке тест пропускается и production-конфигурация не
 * изменяется. Для запуска: задайте PEDMS_TEST_DB_URL, PEDMS_TEST_DB_USERNAME,
 * PEDMS_TEST_DB_PASSWORD.
 */
class PostgreSqlSchemaIntegrationTests {

    @Test
    fun `test schema supports transaction and constraint`() {
        val url = System.getenv("PEDMS_TEST_DB_URL")
        val username = System.getenv("PEDMS_TEST_DB_USERNAME")
        val password = System.getenv("PEDMS_TEST_DB_PASSWORD")
        assumeTrue(!url.isNullOrBlank(), "PEDMS_TEST_DB_URL не задан")

        DriverManager.getConnection(url, username ?: "", password ?: "").use { connection ->
            connection.autoCommit = false
            try {
                val schema = "pedms_stage11_test"
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                    statement.execute("CREATE SCHEMA $schema")
                    statement.execute("SET search_path TO $schema")
                    statement.execute(
                        "CREATE TABLE stage11_document (" +
                            "id integer PRIMARY KEY, title varchar(200) NOT NULL, " +
                            "status varchar(30) NOT NULL CHECK (status IN ('DRAFT','SIGNED')))"
                    )
                    statement.execute(
                        "INSERT INTO stage11_document(id,title,status) VALUES (1,'Тестовый документ','DRAFT')"
                    )
                    val result = statement.executeQuery(
                        "SELECT title,status FROM stage11_document WHERE id = 1"
                    )
                    result.use {
                        assertEquals(true, it.next())
                        assertEquals("Тестовый документ", it.getString("title"))
                        assertEquals("DRAFT", it.getString("status"))
                    }
                }
                connection.rollback()
            } finally {
                connection.autoCommit = true
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS pedms_stage11_test CASCADE") }
            }
        }
    }
}
