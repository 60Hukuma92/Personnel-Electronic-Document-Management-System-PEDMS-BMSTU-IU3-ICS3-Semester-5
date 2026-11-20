package bmstu.iu3.moiseev.pedms

import bmstu.iu3.moiseev.pedms.config.DataSeeder
import bmstu.iu3.moiseev.pedms.config.SchemaVerifier
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean

@SpringBootTest
class PedmsApplicationTests {

	@MockBean
	lateinit var dataSeeder: DataSeeder

	@MockBean
	lateinit var schemaVerifier: SchemaVerifier

	@Test
	fun contextLoads() {
	}

}
