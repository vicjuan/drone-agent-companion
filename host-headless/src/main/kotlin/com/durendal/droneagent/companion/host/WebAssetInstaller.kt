package com.durendal.droneagent.companion.host

import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

fun interface WebAssetSource {
    @Throws(IOException::class)
    fun open(assetPath: String): InputStream
}

class AndroidWebAssetSource(private val assets: AssetManager) : WebAssetSource {
    override fun open(assetPath: String): InputStream =
        assets.open(assetPath, AssetManager.ACCESS_STREAMING)
}

internal data class WebAssetEntry(
    val assetPath: String,
    val relativeOutputPath: String,
    val sha256: String,
) {
    init {
        validateRelativePath(assetPath, "assetPath")
        validateRelativePath(relativeOutputPath, "relativeOutputPath")
        require(SHA_256_REGEX.matches(sha256)) { "sha256 must be 64 lowercase hex characters" }
    }

    companion object {
        private val SHA_256_REGEX = Regex("[0-9a-f]{64}")

        private fun validateRelativePath(path: String, label: String) {
            require(path.isNotBlank()) { "$label must not be blank" }
            require(!path.startsWith('/') && !path.contains('\\')) {
                "$label must be a portable relative path"
            }
            require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "$label contains an unsafe segment"
            }
        }
    }
}

internal data class WebAssetBundle(
    val entries: List<WebAssetEntry>,
) {
    init {
        require(entries.isNotEmpty()) { "web asset allowlist must not be empty" }
        require(entries.map { it.assetPath }.toSet().size == entries.size) {
            "web asset source paths must be unique"
        }
        require(entries.map { it.relativeOutputPath }.toSet().size == entries.size) {
            "web asset output paths must be unique"
        }
    }

    val fingerprint: String by lazy {
        sha256Hex(
            entries.sortedBy { it.relativeOutputPath }.joinToString("\n") {
                "${it.assetPath}\u0000${it.relativeOutputPath}\u0000${it.sha256}"
            }.toByteArray(Charsets.UTF_8),
        )
    }
}

/**
 * Installs the packaged web console into an immutable, content-addressed release.
 *
 * Production callers cannot supply a manifest: the exact allowlist and SHA-256
 * values below are the admission boundary. Files are copied to a sibling staging
 * directory, fsynced, verified, then published with a same-filesystem atomic
 * rename. There is deliberately no non-atomic fallback.
 */
class WebAssetInstaller private constructor(
    private val source: WebAssetSource,
    installRoot: File,
    private val bundle: WebAssetBundle,
) {
    private val installRoot = installRoot.absoluteFile

    constructor(source: WebAssetSource, installRoot: File) :
        this(source, installRoot, PACKAGED_WEB_CONSOLE)

    @Throws(IOException::class)
    fun install(): File = synchronized(INSTALL_LOCK) {
        ensureInstallRoot()
        cleanAbandonedStagingDirectories()

        val release = checkedChild(installRoot, "$RELEASE_PREFIX${bundle.fingerprint}")
        if (release.exists()) {
            verifyRelease(release)
            retainAtMostTwoReleases(release)
            return@synchronized release
        }

        val staging =
            checkedChild(
                installRoot,
                "$STAGING_PREFIX${bundle.fingerprint}-${Thread.currentThread().id}-${System.nanoTime()}",
            )
        if (!staging.mkdir()) throw IOException("cannot create web asset staging directory")

        try {
            bundle.entries.forEach { entry -> copyVerified(entry, staging) }
            verifyRelease(staging)
            publishAtomically(staging, release)
            verifyRelease(release)
            retainAtMostTwoReleases(release)
            release
        } finally {
            if (staging.exists()) deleteTree(staging)
        }
    }

    @Throws(IOException::class)
    private fun copyVerified(entry: WebAssetEntry, staging: File) {
        val output = checkedChild(staging, entry.relativeOutputPath)
        val parent = output.parentFile ?: throw IOException("web asset output has no parent")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create web asset output directory")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        source.open(entry.assetPath).use { input ->
            FileOutputStream(output, false).use { destination ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    destination.write(buffer, 0, count)
                }
                destination.flush()
                destination.fd.sync()
            }
        }
        val actual = digest.digest().toHex()
        if (actual != entry.sha256) {
            throw IOException("packaged web asset hash mismatch: ${entry.relativeOutputPath}")
        }
    }

    @Throws(IOException::class)
    private fun publishAtomically(staging: File, release: File) {
        try {
            Files.move(staging.toPath(), release.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            // Another in-process installer won the race. Its immutable release
            // must still pass the same exact-manifest verification below.
            verifyRelease(release)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IOException("web assets require a same-filesystem atomic rename", unsupported)
        }
    }

    @Throws(IOException::class)
    private fun verifyRelease(directory: File) {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) {
            throw IOException("web asset release is not a real directory")
        }

        val expectedFiles = bundle.entries.map { it.relativeOutputPath }.toSet()
        val expectedDirectories =
            expectedFiles.flatMap { path ->
                val parts = path.split('/')
                (1 until parts.size).map { end -> parts.take(end).joinToString("/") }
            }.toSet()
        val actualFiles = mutableSetOf<String>()
        val actualDirectories = mutableSetOf<String>()

        Files.walk(directory.toPath()).use { paths ->
            paths.forEach { path ->
                if (path == directory.toPath()) return@forEach
                if (Files.isSymbolicLink(path)) throw IOException("web asset release contains a symlink")
                val relative = directory.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                when {
                    Files.isRegularFile(path) -> actualFiles += relative
                    Files.isDirectory(path) -> actualDirectories += relative
                    else -> throw IOException("web asset release contains an unsupported entry")
                }
            }
        }
        if (actualFiles != expectedFiles || actualDirectories != expectedDirectories) {
            throw IOException("web asset release differs from the fixed allowlist")
        }

        bundle.entries.forEach { entry ->
            val file = checkedChild(directory, entry.relativeOutputPath)
            val actual = file.inputStream().use(::sha256Hex)
            if (actual != entry.sha256) {
                throw IOException("installed web asset hash mismatch: ${entry.relativeOutputPath}")
            }
        }
    }

    @Throws(IOException::class)
    private fun ensureInstallRoot() {
        if (installRoot.exists()) {
            if (!installRoot.isDirectory || Files.isSymbolicLink(installRoot.toPath())) {
                throw IOException("web asset install root is not a real directory")
            }
            return
        }
        if (!installRoot.mkdirs() && !installRoot.isDirectory) {
            throw IOException("cannot create web asset install root")
        }
    }

    @Throws(IOException::class)
    private fun cleanAbandonedStagingDirectories() {
        installRoot.listFiles().orEmpty()
            .filter { it.name.startsWith(STAGING_PREFIX) }
            .forEach(::deleteTree)
    }

    @Throws(IOException::class)
    private fun retainAtMostTwoReleases(current: File) {
        installRoot.listFiles().orEmpty()
            .filter { it != current && it.name.startsWith(RELEASE_PREFIX) }
            .sortedByDescending(File::lastModified)
            .drop(1)
            .forEach(::deleteTree)
    }

    @Throws(IOException::class)
    private fun checkedChild(parent: File, relativePath: String): File {
        val root = parent.canonicalFile.toPath()
        val candidate = File(parent, relativePath).canonicalFile
        if (!candidate.toPath().startsWith(root) || candidate.toPath() == root) {
            throw IOException("web asset path escapes its root")
        }
        return candidate
    }

    @Throws(IOException::class)
    private fun deleteTree(target: File) {
        val root = installRoot.canonicalFile.toPath()
        val path = target.canonicalFile.toPath()
        if (path == root || !path.startsWith(root)) {
            throw IOException("refusing to delete outside web asset install root")
        }
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return
        Files.walk(target.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    internal companion object {
        private const val RELEASE_PREFIX = "release-"
        private const val STAGING_PREFIX = ".stage-"
        private const val COPY_BUFFER_BYTES = 16 * 1024
        private val INSTALL_LOCK = Any()

        fun forTesting(
            source: WebAssetSource,
            installRoot: File,
            entries: List<WebAssetEntry>,
        ): WebAssetInstaller = WebAssetInstaller(source, installRoot, WebAssetBundle(entries))

        val PACKAGED_WEB_CONSOLE =
            WebAssetBundle(
                listOf(
                    packaged("index.html", "72d76342c89f6a74635bd80abf99f7b348d001a4c1dea0a4260d56b217bdcd33"),
                    packaged("styles.css", "95082b469216b040802be61475442fa8a7841f2f1dcbb043c93e4c367ed89bfc"),
                    packaged("assets/capability-matrix.js", "e97b8c6ba9ab022613999b7fba9e08a8dab92df1f12e484ce9740921cba52116"),
                    packaged("assets/console-actions.js", "54b702b39cc4336b125d8871543e77cee3a5a0201130b406a6aa458d81d0a0c9"),
                    packaged("assets/console-client.js", "077faf4fa3865c35b523b6d27f9d3c40fbd8d2455b0183e5d352a05e4b6d5cc7"),
                    packaged("assets/console-protocol.js", "3e71a4e7e1130303d8b0de590fad90ff91db88fac4705409c160faaf384ed574"),
                    packaged("assets/console-state.js", "bf64847276b61632512bdaad5448b9d47a150e5c858e560819069d6cf7c90075"),
                    packaged("assets/continuous-hold.js", "1a9bdcbd618704acf541219a1d7c8aa42c44e2072b2b16f3cd55c9c01d633cd5"),
                    packaged("assets/main.js", "722a94f986a10090b0fb63fb430e8fba831ae078aad8efd4a9ff168beb1287cc"),
                    packaged("assets/video-playback.js", "67eda0d42cda80324e6bdc66086ed35bdca31ff13b7113c5aab70528aef872ca"),
                    packaged("capability-matrix/g520-stack.json", "edc30d4bc9a1e1f0db4823e0428e74b54c5d512a3e9d2a21dcacf27ff26de14a"),
                ),
            )

        private fun packaged(relativePath: String, sha256: String) =
            WebAssetEntry(
                assetPath = "companion-web/$relativePath",
                relativeOutputPath = relativePath,
                sha256 = sha256,
            )
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        digest.update(buffer, 0, count)
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
