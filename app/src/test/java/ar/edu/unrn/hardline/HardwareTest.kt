package ar.edu.unrn.hardline

import org.junit.Assert.*
import org.junit.Test

class HardwareTest {
    @Test fun gravityMappingAndSaturation() {
        assertEquals(0, SensorMapping.millivolts(-SensorMapping.GRAVITY, 3300))
        assertEquals(1650, SensorMapping.millivolts(0f, 3300))
        assertEquals(3300, SensorMapping.millivolts(SensorMapping.GRAVITY, 3300))
        assertEquals(1800, SensorMapping.millivolts(50f, 1800))
        assertEquals(0, SensorMapping.millivolts(-50f, 1800))
    }
    @Test fun pairingQr() {
        val result = Pairing.parse("bridge://pair?v=1.3&host=192.168.1.45&port=8767&service=hardboiled&pin=123456&name=A%20B")
        assertEquals("ws://192.168.1.45:8767/ws", result.endpoint)
        assertEquals("123456", result.pin)
    }
    @Test fun ipv6Endpoint() { assertEquals("ws://[::1]:8767/ws", Pairing("::1",8767,"123456").endpoint) }
    @Test fun rejectsInvalidInputs() {
        for (uri in listOf("https://example.org", "bridge://pair?v=1.3&host=a&port=8767&service=meet&pin=123456", "bridge://pair?v=1.3&host=a&port=0&service=hardboiled&pin=123456")) {
            assertThrows(IllegalArgumentException::class.java) { Pairing.parse(uri) }
        }
        assertThrows(IllegalArgumentException::class.java) { SensorMapping.millivolts(Float.NaN,3300) }
        assertThrows(IllegalArgumentException::class.java) { Pairing("host/path",8767,"123456") }
    }
}
