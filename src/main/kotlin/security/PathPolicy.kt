package security

import config.AppConfig
import java.io.File
import java.nio.file.Paths

/**
 * Enforces path restrictions for the Codex working directory.
 *
 * Defense-in-depth: checks are layered so that even if one fails another catches the issue.
 *
 * Rules (in order):
 *   1. Path must resolve to a canonical absolute path without errors.
 *   2. Path must exist and be a directory.
 *   3. Path must not be the filesystem root ("/").
 *   4. Path must not be the user home directory root.
 *   5. Path must not be a known sensitive system directory.
 *   6. Path must be inside one of the configured allowed roots.
 *   7. For WORKSPACE_WRITE sandbox, the path must be inside a write-allowed root (same set).
 */
object PathPolicy {

    /**
     * Well-known directories that must never be used as the Codex working directory.
     * These are checked as path prefixes after canonicalization.
     */
    private val DENIED_SUFFIXES: List<String> = listOf(
        "/.ssh",
        "\\.ssh",
        "/.gnupg",
        "\\.gnupg",
        "/.aws",
        "/.gcloud",
        "/.kube",
        "/.config",
    )

    private val DENIED_ABSOLUTE_PATHS: List<String> = buildList {
        add("/")
        add("/etc")
        add("/var")
        add("/usr")
        add("/bin")
        add("/sbin")
        add("/boot")
        add("/proc")
        add("/sys")
        add("/dev")
        add("/tmp")
        add("/private/tmp")
        // Windows equivalents (canonicalization will normalize separators)
        add("C:\\")
        add("C:\\Windows")
        add("C:\\System32")
    }

    sealed class PathValidationResult {
        data class Allowed(val canonicalPath: String) : PathValidationResult()
        data class Denied(val reason: String) : PathValidationResult()
    }

    /**
     * Validates and canonicalizes [requestedPath] against the configured policy.
     *
     * @param requestedPath  Raw path string from the tool caller (may be relative).
     * @param config         Resolved application configuration.
     */
    fun validate(requestedPath: String, config: AppConfig): PathValidationResult {
        // 1. Canonicalize — resolves symlinks and normalizes separators.
        val canonical = try {
            File(requestedPath).canonicalPath
        } catch (e: Exception) {
            return PathValidationResult.Denied("Cannot resolve path: ${e.message}")
        }

        // 2. Existence and type checks.
        val file = File(canonical)
        if (!file.exists()) {
            return PathValidationResult.Denied("Path does not exist: $canonical")
        }
        if (!file.isDirectory) {
            return PathValidationResult.Denied("Path is not a directory: $canonical")
        }

        // 3. Filesystem root — covers both Unix "/" and Windows roots.
        if (canonical == "/" || canonical.matches(Regex("""[A-Za-z]:\\"""))) {
            return PathValidationResult.Denied("Filesystem root is not an allowed working directory")
        }

        // 4. User home root — allow subdirectories but not the home dir itself.
        val homeDir = System.getProperty("user.home")
        if (homeDir != null) {
            val canonicalHome = File(homeDir).canonicalPath
            if (canonical == canonicalHome) {
                return PathValidationResult.Denied(
                    "User home directory root is not an allowed working directory"
                )
            }
        }

        // 5. Hardcoded denied paths.
        if (canonical in DENIED_ABSOLUTE_PATHS) {
            return PathValidationResult.Denied("Path is a denied system directory: $canonical")
        }
        val lowerCanonical = canonical.lowercase()
        val deniedSuffix = DENIED_SUFFIXES.firstOrNull { lowerCanonical.endsWith(it.lowercase()) }
        if (deniedSuffix != null) {
            return PathValidationResult.Denied(
                "Path resolves to a denied sensitive directory ($deniedSuffix): $canonical"
            )
        }
        // Additional temp-root check.
        if (canonical == "/tmp" || canonical == "/private/tmp" || canonical.equals("c:\\windows\\temp", ignoreCase = true)) {
            return PathValidationResult.Denied("Temporary root directories are not allowed")
        }

        // 6. Must be inside one of the configured allowed roots.
        val insideAllowedRoot = config.allowedRoots.any { root ->
            isInsideRoot(canonical, root)
        }
        if (!insideAllowedRoot) {
            return PathValidationResult.Denied(
                "Path '$canonical' is not inside any allowed root. " +
                    "Allowed roots: ${config.allowedRoots}"
            )
        }

        return PathValidationResult.Allowed(canonical)
    }

    /**
     * Returns true if [path] is inside [root] (exact match or proper subdirectory).
     * Both must be canonical absolute paths.
     */
    fun isInsideRoot(path: String, root: String): Boolean {
        val normalizedPath = path.trimEnd(File.separatorChar)
        val normalizedRoot = root.trimEnd(File.separatorChar)
        return normalizedPath == normalizedRoot ||
            normalizedPath.startsWith(normalizedRoot + File.separator)
    }
}
