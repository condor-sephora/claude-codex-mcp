package intake

import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class IntakeOutputFormatTest {

    @Test
    fun `default is yaml`() {
        assertEquals(IntakeOutputFormat.YAML, IntakeOutputFormat.default())
    }

    @Test
    fun `parses yaml case-insensitively`() {
        assertEquals(IntakeOutputFormat.YAML, IntakeOutputFormat.fromString("yaml"))
        assertEquals(IntakeOutputFormat.YAML, IntakeOutputFormat.fromString("YAML"))
        assertEquals(IntakeOutputFormat.YAML, IntakeOutputFormat.fromString("Yaml"))
    }

    @Test
    fun `parses json`() {
        assertEquals(IntakeOutputFormat.JSON, IntakeOutputFormat.fromString("json"))
    }

    @Test
    fun `parses markdown`() {
        assertEquals(IntakeOutputFormat.MARKDOWN, IntakeOutputFormat.fromString("markdown"))
    }

    @Test
    fun `returns null for unknown value`() {
        assertNull(IntakeOutputFormat.fromString("xml"))
    }

    @Test
    fun `returns null for null input`() {
        assertNull(IntakeOutputFormat.fromString(null))
    }

    @Test
    fun `value strings are lowercase`() {
        assertEquals("yaml", IntakeOutputFormat.YAML.value)
        assertEquals("json", IntakeOutputFormat.JSON.value)
        assertEquals("markdown", IntakeOutputFormat.MARKDOWN.value)
    }
}
