package bmstu.iu3.moiseev.pedms.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignatureServiceTests {
    private val service = SignatureService()

    @Test
    fun `generated code consists of six digits`() {
        val code = service.generateCode()

        assertEquals(6, code.length)
        assertTrue(code.all(Char::isDigit))
    }

    @Test
    fun `verification accepts exact code and trims entered value`() {
        assertTrue(service.verifyCode("123456", " 123456 "))
        assertFalse(service.verifyCode("123456", "123457"))
        assertFalse(service.verifyCode("12345", "12345"))
    }
}
