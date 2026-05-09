package security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Path

class PathPolicyTest {

    @TempDir
    lateinit var tempDir: Path

    private val config = testConfig()

    private fun dir() = tempDir.toFile().canonicalPath

    // ---------- validateCwd ----------

    @Test
    fun `accepts existing directory`() {
        val result = PathPolicy.validateCwd(dir(), config)
        assertTrue(result is PathPolicy.CwdResult.Ok)
        assertEquals(dir(), (result as PathPolicy.CwdResult.Ok).canonicalCwd)
    }

    @Test
    fun `defaults to server cwd when cwd is null`() {
        val result = PathPolicy.validateCwd(null, config)
        assertTrue(result is PathPolicy.CwdResult.Ok)
    }

    @Test
    fun `defaults to server cwd when cwd is blank`() {
        val result = PathPolicy.validateCwd("   ", config)
        assertTrue(result is PathPolicy.CwdResult.Ok)
    }

    @Test
    fun `rejects non-existent directory`() {
        val result = PathPolicy.validateCwd("/does/not/exist/ever", config)
        assertTrue(result is PathPolicy.CwdResult.Rejected)
    }

    @Test
    fun `rejects file path as cwd`() {
        val f = File(dir(), "somefile.txt").also { it.writeText("x") }
        val result = PathPolicy.validateCwd(f.absolutePath, config)
        assertTrue(result is PathPolicy.CwdResult.Rejected)
    }

    @Test
    fun `enforces allowed roots when configured`() {
        val restrictedConfig = config.copy(allowedRoots = listOf("/var/projects"))
        val result = PathPolicy.validateCwd(dir(), restrictedConfig)
        assertTrue(result is PathPolicy.CwdResult.Rejected,
            "cwd outside allowed roots should be rejected")
        val reason = (result as PathPolicy.CwdResult.Rejected).reason
        assertTrue(reason.contains("allowed root"), "Rejection reason should mention allowed roots")
    }

    @Test
    fun `accepts cwd equal to allowed root`() {
        val root = dir()
        val restrictedConfig = config.copy(allowedRoots = listOf(root))
        val result = PathPolicy.validateCwd(root, restrictedConfig)
        assertTrue(result is PathPolicy.CwdResult.Ok)
    }

    @Test
    fun `accepts cwd inside allowed root`() {
        val subDir = File(dir(), "sub").also { it.mkdirs() }
        val restrictedConfig = config.copy(allowedRoots = listOf(dir()))
        val result = PathPolicy.validateCwd(subDir.absolutePath, restrictedConfig)
        assertTrue(result is PathPolicy.CwdResult.Ok)
    }

    // ---------- validateRequestFile ----------

    private fun writeFile(name: String, content: String = "# intake request"): String {
        val f = File(dir(), name)
        f.parentFile.mkdirs()
        f.writeText(content)
        return f.canonicalPath
    }

    @Test
    fun `accepts valid relative md file inside cwd`() {
        writeFile("intake-request.md")
        val result = PathPolicy.validateRequestFile("intake-request.md", dir(), config)
        assertTrue(result is PathPolicy.RequestFileResult.Ok)
    }

    @Test
    fun `accepts nested relative path`() {
        writeFile(".agent-intake/TASK-1/intake-request.md")
        val result = PathPolicy.validateRequestFile(
            ".agent-intake/TASK-1/intake-request.md", dir(), config
        )
        assertTrue(result is PathPolicy.RequestFileResult.Ok)
    }

    @Test
    fun `rejects null requestFile`() {
        val result = PathPolicy.validateRequestFile(null, dir(), config)
        assertTrue(result is PathPolicy.RequestFileResult.Rejected)
    }

    @Test
    fun `rejects blank requestFile`() {
        val result = PathPolicy.validateRequestFile("   ", dir(), config)
        assertTrue(result is PathPolicy.RequestFileResult.Rejected)
    }

    @Test
    fun `rejects absolute requestFile path`() {
        writeFile("abs.md")
        val abs = File(dir(), "abs.md").canonicalPath
        val result = PathPolicy.validateRequestFile(abs, dir(), config)
        assertTrue(result is PathPolicy.RequestFileResult.Rejected,
            "Absolute requestFile must be rejected")
        val reason = (result as PathPolicy.RequestFileResult.Rejected).reason
        assertTrue(reason.contains("relative"), "Rejection should mention relative path")
    }

    @Test
    fun `rejects path traversal with dotdot`() {
        val result = PathPolicy.validateRequestFile("../escape.md", dir(), config)
        assertTrue(result is PathPolicy.RequestFileResult.Rejected,
            "Path traversal with ../ must be rejected")
        val reason = (result as PathPolicy.RequestFileResult.Rejected).reason
        assertTrue(reason.contains("outside cwd") || reason.contains("not found"),
            "Rejection should indicate escape or not-found: $reason")
    }

    @Test
    fun `rejects non-existent requestFile`() {
        val result = PathPolicy.validateRequestFile("no-such-file.md", dir(), config)
        assertTrue(result is PathPolicy.RequestFileResult.Rejected)
        val reason = (result as PathPolicy.RequestFileResult.Rejected).reason
        assertTrue(reason.contains("not found"))
    }

    @ParameterizedTest
    @ValueSource(strings = [".env", ".env.local", "secrets.md", "credentials.yaml",
        "id_rsa.txt", "local.properties", "gradle.properties"])
    fun `rejects sensitive file names`(name: String) {
        writeFile(name)
        val result = PathPolicy.validateRequestFile(name, dir(), config)
        assertTrue(result is PathPolicy.RequestFileResult.Rejected,
            "Sensitive file '$name' should be rejected")
        val reason = (result as PathPolicy.RequestFileResult.Rejected).reason
        assertTrue(reason.contains("sensitive") || reason.contains("denied"), reason)
    }

    @ParameterizedTest
    @ValueSource(strings = ["request.exe", "request.sh", "request.bin", "request", "request."])
    fun `rejects disallowed extensions`(name: String) {
        writeFile(name)
        val result = PathPolicy.validateRequestFile(name, dir(), config)
        assertTrue(result is PathPolicy.RequestFileResult.Rejected,
            "Extension from '$name' should be rejected")
    }

    @ParameterizedTest
    @ValueSource(strings = ["request.md", "request.txt", "request.yaml", "request.yml", "request.json"])
    fun `accepts allowed extensions`(name: String) {
        writeFile(name)
        val result = PathPolicy.validateRequestFile(name, dir(), config)
        assertTrue(result is PathPolicy.RequestFileResult.Ok,
            "Extension from '$name' should be accepted")
    }

    @Test
    fun `rejects file exceeding size limit`() {
        val f = File(dir(), "big.md")
        f.writeBytes(ByteArray(201 * 1024) { 'a'.code.toByte() })
        val smallLimitConfig = config.copy(maxRequestFileBytes = 100 * 1024L)
        val result = PathPolicy.validateRequestFile("big.md", dir(), smallLimitConfig)
        assertTrue(result is PathPolicy.RequestFileResult.Rejected,
            "File exceeding size limit should be rejected")
    }

    @Test
    fun `relativePath in Ok result is cwd-relative`() {
        writeFile(".agent-intake/TASK-1/intake-request.md")
        val result = PathPolicy.validateRequestFile(
            ".agent-intake/TASK-1/intake-request.md", dir(), config
        ) as PathPolicy.RequestFileResult.Ok
        assertEquals(".agent-intake/TASK-1/intake-request.md", result.relativePath)
    }
}
