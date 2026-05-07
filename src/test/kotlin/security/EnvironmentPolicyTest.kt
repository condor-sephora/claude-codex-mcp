package security

import org.junit.jupiter.api.Assertions.*
import kotlin.test.Test

class EnvironmentPolicyTest {

    @Test
    fun `only forwards allowlisted variables`() {
        val parent = mapOf(
            "PATH" to "/usr/bin",
            "HOME" to "/home/user",
            "SECRET_TOKEN" to "should-not-appear",
            "AWS_SECRET_ACCESS_KEY" to "also-should-not-appear",
            "OPENAI_API_KEY" to "sk-test",
        )
        val config = testConfig().copy(
            envPassthroughAllowlist = setOf("PATH", "HOME", "OPENAI_API_KEY"),
        )

        val result = EnvironmentPolicy.buildEnv(config, parent)

        assertEquals("/usr/bin", result["PATH"])
        assertEquals("/home/user", result["HOME"])
        assertEquals("sk-test", result["OPENAI_API_KEY"])
        assertNull(result["SECRET_TOKEN"], "SECRET_TOKEN must not be forwarded")
        assertNull(result["AWS_SECRET_ACCESS_KEY"], "AWS_SECRET_ACCESS_KEY must not be forwarded")
    }

    @Test
    fun `omits allowlisted variable not present in parent`() {
        val parent = mapOf("PATH" to "/usr/bin")
        val config = testConfig().copy(
            envPassthroughAllowlist = setOf("PATH", "OPENAI_API_KEY"),
        )

        val result = EnvironmentPolicy.buildEnv(config, parent)
        assertTrue(result.containsKey("PATH"))
        assertFalse(result.containsKey("OPENAI_API_KEY"), "Should be absent since not in parent")
    }

    @Test
    fun `returns empty map when parent has no allowlisted variables`() {
        val parent = mapOf("RANDOM_VAR" to "value")
        val config = testConfig().copy(
            envPassthroughAllowlist = setOf("PATH"),
        )

        val result = EnvironmentPolicy.buildEnv(config, parent)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolvedKeys returns only keys that would be forwarded`() {
        val parent = mapOf("PATH" to "/usr/bin", "HOME" to "/home/user")
        val config = testConfig().copy(
            envPassthroughAllowlist = setOf("PATH", "HOME", "MISSING"),
        )

        val keys = EnvironmentPolicy.resolvedKeys(config, parent)
        assertEquals(setOf("PATH", "HOME"), keys)
        assertFalse(keys.contains("MISSING"))
    }

    @Test
    fun `does not forward variables outside allowlist even with common names`() {
        val parent = mapOf(
            "LD_PRELOAD" to "evil.so",
            "DYLD_INSERT_LIBRARIES" to "evil.dylib",
            "JAVA_OPTS" to "-verbose:class",
        )
        val config = testConfig().copy(envPassthroughAllowlist = setOf("PATH"))
        val result = EnvironmentPolicy.buildEnv(config, parent)
        assertTrue(result.isEmpty())
    }
}
