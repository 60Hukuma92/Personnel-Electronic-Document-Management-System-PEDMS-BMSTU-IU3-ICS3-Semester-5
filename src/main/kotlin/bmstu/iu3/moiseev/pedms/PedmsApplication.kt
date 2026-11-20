package bmstu.iu3.moiseev.pedms

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class PedmsApplication

fun main(args: Array<String>) {
	runApplication<PedmsApplication>(*args)
}
