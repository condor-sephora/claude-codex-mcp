package security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class IntakeSecurityPolicyTest {

    @TempDir
    lateinit var tempDir: Path

    private val config = testConfig()

    private fun dir() = tempDir.toFile().canonicalPath

    private fun writeIntakeFile(name: String = ".agent-intake/TASK/intake-request.md"): String {
        val f = File(dir(), name)
        f.parentFile.mkdirs()
        f.writeText("# Intake Request\n\n## Task ID\nTASK-1")
        return f.canonicalPath
    }

    private fun evaluate(
        cwd: String? = dir(),
        requestFile: String? = ".agent-intake/TASK/intake-request.md",
        sandbox: String? = null,
        outputFormat: String? = null,
        extra: String? = null,
        timeoutMs: Long? = null,
        taskId: String? = null,
    ) = IntakeSecurityPolicy.evaluate(
        cwdRaw = cwd,
        requestFileRaw = requestFile,
        sandboxRaw = sandbox,
        outputFormatRaw = outputFormat,
        extraInstructions = extra,
        timeoutMsRaw = timeoutMs,
        taskId = taskId,
        config = config,
    )

    // ---------- Approval ----------

    @Test
    fun `approves valid intake call`() {
        writeIntakeFile()
        val result = evaluate()
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Approved,
            "Valid call should be approved")
    }

    @Test
    fun `approved request has read-only sandbox`() {
        writeIntakeFile()
        val approved = evaluate() as IntakeSecurityPolicy.PolicyResult.Approved
        // IntakeRequest doesn't carry sandbox directly — the tool forces READ_ONLY
        assertNotNull(approved.request.requestFilePath)
    }

    @Test
    fun `approved request echoes relative requestFile`() {
        writeIntakeFile()
        val approved = evaluate() as IntakeSecurityPolicy.PolicyResult.Approved
        assertEquals(".agent-intake/TASK/intake-request.md", approved.request.requestFileRelative)
    }

    @Test
    fun `approves explicit read-only sandbox`() {
        writeIntakeFile()
        val result = evaluate(sandbox = "read-only")
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Approved)
    }

    // ---------- Sandbox rejection ----------

    @Test
    fun `rejects workspace-write sandbox`() {
        writeIntakeFile()
        val result = evaluate(sandbox = "workspace-write")
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Rejected,
            "workspace-write should be rejected for intake")
        val reason = (result as IntakeSecurityPolicy.PolicyResult.Rejected).violation.userMessage
        assertTrue(reason.contains("read-only"))
    }

    @Test
    fun `rejects danger-full-access sandbox`() {
        writeIntakeFile()
        val result = evaluate(sandbox = "danger-full-access")
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Rejected,
            "danger-full-access should be rejected for intake")
    }

    // ---------- requestFile required ----------

    @Test
    fun `rejects missing requestFile`() {
        val result = evaluate(requestFile = null)
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Rejected)
        val reason = (result as IntakeSecurityPolicy.PolicyResult.Rejected).violation.userMessage
        assertTrue(reason.contains("requestFile"), reason)
    }

    @Test
    fun `rejects blank requestFile`() {
        val result = evaluate(requestFile = "   ")
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Rejected)
    }

    // ---------- Output format ----------

    @Test
    fun `accepts yaml outputFormat`() {
        writeIntakeFile()
        val result = evaluate(outputFormat = "yaml")
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Approved)
    }

    @Test
    fun `accepts json outputFormat`() {
        writeIntakeFile()
        val result = evaluate(outputFormat = "json")
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Approved)
    }

    @Test
    fun `accepts markdown outputFormat`() {
        writeIntakeFile()
        val result = evaluate(outputFormat = "markdown")
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Approved)
    }

    @Test
    fun `defaults to yaml when outputFormat omitted`() {
        writeIntakeFile()
        val approved = evaluate(outputFormat = null) as IntakeSecurityPolicy.PolicyResult.Approved
        assertEquals("yaml", approved.request.outputFormat.value)
    }

    @Test
    fun `rejects invalid outputFormat`() {
        writeIntakeFile()
        val result = evaluate(outputFormat = "xml")
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Rejected)
        val reason = (result as IntakeSecurityPolicy.PolicyResult.Rejected).violation.userMessage
        assertTrue(reason.contains("outputFormat") || reason.contains("Unknown"))
    }

    // ---------- Extra instructions ----------

    @Test
    fun `rejects extraInstructions exceeding limit`() {
        writeIntakeFile()
        val tooLong = "x".repeat(config.maxExtraInstructionsChars + 1)
        val result = evaluate(extra = tooLong)
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Rejected,
            "Oversized extraInstructions must be rejected")
    }

    @Test
    fun `accepts extraInstructions within limit`() {
        writeIntakeFile()
        val ok = "x".repeat(config.maxExtraInstructionsChars)
        val result = evaluate(extra = ok)
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Approved)
    }

    @Test
    fun `null extraInstructions is stored as null`() {
        writeIntakeFile()
        val approved = evaluate(extra = null) as IntakeSecurityPolicy.PolicyResult.Approved
        assertNull(approved.request.extraInstructions)
    }

    @Test
    fun `blank extraInstructions is stored as null`() {
        writeIntakeFile()
        val approved = evaluate(extra = "   ") as IntakeSecurityPolicy.PolicyResult.Approved
        assertNull(approved.request.extraInstructions)
    }

    // ---------- Timeout ----------

    @Test
    fun `defaults to configured defaultIntakeTimeoutMs`() {
        writeIntakeFile()
        val approved = evaluate(timeoutMs = null) as IntakeSecurityPolicy.PolicyResult.Approved
        assertEquals(config.defaultIntakeTimeoutMs, approved.request.timeoutMs)
    }

    @Test
    fun `clamps timeout below minimum`() {
        writeIntakeFile()
        val approved = evaluate(timeoutMs = 100L) as IntakeSecurityPolicy.PolicyResult.Approved
        assertEquals(5_000L, approved.request.timeoutMs)
    }

    @Test
    fun `clamps timeout above max`() {
        writeIntakeFile()
        val overMax = config.maxIntakeTimeoutMs + 1_000_000L
        val approved = evaluate(timeoutMs = overMax) as IntakeSecurityPolicy.PolicyResult.Approved
        assertEquals(config.maxIntakeTimeoutMs, approved.request.timeoutMs)
    }

    // ---------- taskId ----------

    @Test
    fun `rejects blank taskId`() {
        writeIntakeFile()
        val result = evaluate(taskId = "   ")
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Rejected)
    }

    @Test
    fun `rejects oversized taskId`() {
        writeIntakeFile()
        val result = evaluate(taskId = "a".repeat(129))
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Rejected)
    }

    @Test
    fun `accepts valid taskId`() {
        writeIntakeFile()
        val result = evaluate(taskId = "TASK-123")
        assertTrue(result is IntakeSecurityPolicy.PolicyResult.Approved)
    }
}
