package security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test

class PathPolicyTest {

    @TempDir
    lateinit var tempDir: Path

    private fun allowed(subPath: String = ""): String {
        val dir = if (subPath.isBlank()) tempDir.toFile()
        else File(tempDir.toFile(), subPath).also { it.mkdirs() }
        return dir.canonicalPath
    }

    private fun config(roots: List<String>? = null) =
        testConfig(allowedRoots = roots ?: listOf(tempDir.toFile().canonicalPath))

    // ---------- Happy path ----------

    @Test
    fun `allows valid path inside allowed root`() {
        val subDir = File(tempDir.toFile(), "workspace").also { it.mkdir() }
        val result = PathPolicy.validate(subDir.canonicalPath, config())
        assertTrue(result is PathPolicy.PathValidationResult.Allowed)
        assertEquals(subDir.canonicalPath, (result as PathPolicy.PathValidationResult.Allowed).canonicalPath)
    }

    @Test
    fun `allows path equal to allowed root`() {
        val result = PathPolicy.validate(tempDir.toFile().canonicalPath, config())
        assertTrue(result is PathPolicy.PathValidationResult.Allowed)
    }

    // ---------- Non-existent and non-directory ----------

    @Test
    fun `rejects non-existent path`() {
        val result = PathPolicy.validate("/this/path/does/not/exist/12345", config())
        assertDenied(result)
    }

    @Test
    fun `rejects file (non-directory)`() {
        val file = File(tempDir.toFile(), "file.txt").also { it.writeText("content") }
        val result = PathPolicy.validate(file.canonicalPath, config())
        assertDenied(result)
    }

    // ---------- System directories ----------

    @Test
    fun `rejects filesystem root`() {
        val cfg = config(roots = listOf(tempDir.toFile().canonicalPath))
        val result = PathPolicy.validate("/", cfg)
        assertDenied(result)
    }

    @Test
    fun `rejects user home root`() {
        val home = System.getProperty("user.home")
        if (home != null) {
            // Need home to be in allowed roots to reach the home-root check
            val cfg = config(roots = listOf(home))
            val result = PathPolicy.validate(home, cfg)
            assertDenied(result)
        }
    }

    @Test
    fun `rejects dot-ssh directory`() {
        val sshDir = File(tempDir.toFile(), ".ssh").also { it.mkdir() }
        val result = PathPolicy.validate(sshDir.canonicalPath, config())
        assertDenied(result)
    }

    @Test
    fun `rejects dot-gnupg directory`() {
        val gnupgDir = File(tempDir.toFile(), ".gnupg").also { it.mkdir() }
        val result = PathPolicy.validate(gnupgDir.canonicalPath, config())
        assertDenied(result)
    }

    // ---------- Outside allowed roots ----------

    @Test
    fun `rejects path outside allowed roots`() {
        val otherDir = createTempDir("other")
        try {
            val result = PathPolicy.validate(otherDir.canonicalPath, config())
            assertDenied(result)
        } finally {
            otherDir.deleteRecursively()
        }
    }

    @Test
    fun `rejects parent traversal above allowed root`() {
        val result = PathPolicy.validate(tempDir.toFile().parent, config())
        assertDenied(result)
    }

    // ---------- isInsideRoot ----------

    @Test
    fun `isInsideRoot returns true for exact match`() {
        assertTrue(PathPolicy.isInsideRoot("/a/b/c", "/a/b/c"))
    }

    @Test
    fun `isInsideRoot returns true for subdirectory`() {
        assertTrue(PathPolicy.isInsideRoot("/a/b/c/d", "/a/b/c"))
    }

    @Test
    fun `isInsideRoot returns false for parent`() {
        assertFalse(PathPolicy.isInsideRoot("/a/b", "/a/b/c"))
    }

    @Test
    fun `isInsideRoot returns false for sibling`() {
        assertFalse(PathPolicy.isInsideRoot("/a/b/d", "/a/b/c"))
    }

    @Test
    fun `isInsideRoot returns false for prefix-but-not-directory match`() {
        // "/a/bc" should not be inside "/a/b"
        assertFalse(PathPolicy.isInsideRoot("/a/bc", "/a/b"))
    }

    // ---------- Helpers ----------

    private fun assertDenied(result: PathPolicy.PathValidationResult) {
        assertTrue(
            result is PathPolicy.PathValidationResult.Denied,
            "Expected Denied but got: $result"
        )
    }
}
