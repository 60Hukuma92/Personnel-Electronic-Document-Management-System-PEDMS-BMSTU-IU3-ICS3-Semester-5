package bmstu.iu3.moiseev.pedms.service

import org.springframework.stereotype.Service
import java.security.SecureRandom

/**
 * Простая ЭП учебного прототипа: код показывается в интерфейсе вместо SMS.
 */
@Service
class SignatureService {
    private val random = SecureRandom()

    fun generateCode(): String = "%06d".format(random.nextInt(1_000_000))

    fun verifyCode(expected: String, actual: String): Boolean =
        expected.length == 6 && expected == actual.trim()
}
