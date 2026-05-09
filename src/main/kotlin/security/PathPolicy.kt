package security

import config.AppConfig
import java.io.File

/**
 * Path validation rules shared by intake mode (and reusable by future tools).
 *
 * The two entry points are pure functions that return either a validated absolute
 * path or a structured rejection — callers never have to repeat the underlying
 * filesystem checks.
 *
 * Security invariants:
 *   - cwd is canonicalized, must exist, must be a directory, and (if any allowed
 *     roots are configured) must be equal to or a descendant of one of them.
 *   - requestFile must be a relative path that resolves under cwd after
 *     canonicalization (defeats `..` escapes and absolute paths).
 *   - requestFile basename must not match any sensitive pattern.
 *   - requestFile extension must be on the allowlist.
 *   - requestFile must be a regular file with size <= maxRequestFileBytes.
 */
object PathPolicy {

    /** File extensions the intake tool will accept. */
    val ALLOWED_REQUEST_EXTENSIONS: Set<String> = setOf("md", "txt", "yaml", "yml", "json")

    /** Patterns that block obvious credential-bearing filenames before we ever touch the file. */
    private val SENSITIVE_PATTERNS: List<Regex> = listOf(
        Regex("""^\.env$""", RegexOption.IGNORE_CASE),
        Regex("""^\.env\..+$""", RegexOption.IGNORE_CASE),
        Regex(""".*secret.*""", RegexOption.IGNORE_CASE),
        Regex(""".*credential.*""", RegexOption.IGNORE_CASE),
        Regex(""".*\.pem$""", RegexOption.IGNORE_CASE),
        Regex(""".*\.key$""", RegexOption.IGNORE_CASE),
        Regex(""".*\.jks$""", RegexOption.IGNORE_CASE),
        Regex(""".*\.keystore$""", RegexOption.IGNORE_CASE),
        Regex("""^id_rsa.*""", RegexOption.IGNORE_CASE),
        Regex("""^id_dsa.*""", RegexOption.IGNORE_CASE),
        Regex("""^id_ecdsa.*""", RegexOption.IGNORE_CASE),
        Regex("""^id_ed25519.*""", RegexOption.IGNORE_CASE),
        Regex("""^local\.properties$""", RegexOption.IGNORE_CASE),
        Regex("""^gradle\.properties$""", RegexOption.IGNORE_CASE),
    )

    sealed class CwdResult {
        data class Ok(val canonicalCwd: String) : CwdResult()
        data class Rejected(val reason: String) : CwdResult()
    }

    sealed class RequestFileResult {
        data class Ok(val canonicalPath: String, val relativePath: String) : RequestFileResult()
        data class Rejected(val reason: String) : RequestFileResult()
    }

    fun validateCwd(cwdRaw: String?, config: AppConfig): CwdResult {
        val effective = cwdRaw?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.dir")
            ?: "."
        val file = File(effective)
        if (!file.exists() || !file.isDirectory) {
            return CwdResult.Rejected("cwd does not exist or is not a directory: $effective")
        }
        val canonical = try {
            file.canonicalPath
        } catch (e: Exception) {
            return CwdResult.Rejected("cwd could not be canonicalized: ${e.message}")
        }

        if (config.allowedRoots.isNotEmpty() && !isInsideAnyRoot(canonical, config.allowedRoots)) {
            return CwdResult.Rejected(
                "cwd is not inside any configured allowed root (CODEX_MCP_ALLOWED_ROOTS): $canonical"
            )
        }

        return CwdResult.Ok(canonical)
    }

    fun validateRequestFile(
        requestFileRaw: String?,
        cwd: String,
        config: AppConfig,
    ): RequestFileResult {
        if (requestFileRaw.isNullOrBlank()) {
            return RequestFileResult.Rejected("requestFile is required for intake mode")
        }
        val raw = requestFileRaw.trim()

        // Reject absolute paths so the request file must live inside the cwd and the
        // audit log records a portable cwd-relative path.
        val asFile = File(raw)
        if (asFile.isAbsolute) {
            return RequestFileResult.Rejected(
                "requestFile must be a path relative to cwd, not an absolute path: $raw"
            )
        }
        if (raw.startsWith("~")) {
            return RequestFileResult.Rejected("requestFile must not start with ~: $raw")
        }

        // Resolve relative to cwd and canonicalize so `..` segments and symlinks are
        // collapsed before the containment check.
        val cwdFile = File(cwd)
        val resolved = File(cwdFile, raw)
        val canonicalCwd = try {
            cwdFile.canonicalPath
        } catch (e: Exception) {
            return RequestFileResult.Rejected("cwd could not be canonicalized: ${e.message}")
        }
        val canonicalFile = try {
            resolved.canonicalFile
        } catch (e: Exception) {
            return RequestFileResult.Rejected("requestFile could not be canonicalized: ${e.message}")
        }
        if (!isInside(canonicalFile.path, canonicalCwd)) {
            return RequestFileResult.Rejected(
                "requestFile resolves outside cwd: ${canonicalFile.path}"
            )
        }

        if (!canonicalFile.exists()) {
            return RequestFileResult.Rejected("requestFile not found: $raw")
        }
        if (!canonicalFile.isFile) {
            return RequestFileResult.Rejected("requestFile is not a regular file: $raw")
        }

        val basename = canonicalFile.name
        val sensitive = SENSITIVE_PATTERNS.firstOrNull { it.containsMatchIn(basename) }
        if (sensitive != null) {
            return RequestFileResult.Rejected(
                "requestFile name is denied by the sensitive-file pattern: $basename"
            )
        }

        val ext = basename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext.isBlank() || ext !in ALLOWED_REQUEST_EXTENSIONS) {
            return RequestFileResult.Rejected(
                "requestFile extension '$ext' is not allowed. " +
                    "Allowed: ${ALLOWED_REQUEST_EXTENSIONS.sorted().joinToString(", ")}"
            )
        }

        val size = canonicalFile.length()
        if (size > config.maxRequestFileBytes) {
            return RequestFileResult.Rejected(
                "requestFile size $size bytes exceeds maximum ${config.maxRequestFileBytes} " +
                    "(CODEX_MCP_MAX_REQUEST_FILE_BYTES)"
            )
        }
        if (containsBinaryContent(canonicalFile, sampleBytes = 4_096)) {
            return RequestFileResult.Rejected(
                "requestFile appears to be binary; only UTF-8 text files are accepted"
            )
        }

        val relative = canonicalFile.path.removePrefix(canonicalCwd).trimStart(File.separatorChar)

        return RequestFileResult.Ok(
            canonicalPath = canonicalFile.path,
            relativePath = relative,
        )
    }

    private fun isInsideAnyRoot(canonicalPath: String, roots: List<String>): Boolean =
        roots.any { isInside(canonicalPath, it) }

    private fun isInside(canonicalPath: String, canonicalRoot: String): Boolean {
        if (canonicalPath == canonicalRoot) return true
        val withSep = canonicalRoot.trimEnd(File.separatorChar) + File.separatorChar
        return canonicalPath.startsWith(withSep)
    }

    private fun containsBinaryContent(file: File, sampleBytes: Int): Boolean {
        return try {
            file.inputStream().use { stream ->
                val buf = ByteArray(sampleBytes)
                val read = stream.read(buf)
                if (read <= 0) return false
                for (i in 0 until read) {
                    val b = buf[i].toInt() and 0xFF
                    if (b == 0x00) return true
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
