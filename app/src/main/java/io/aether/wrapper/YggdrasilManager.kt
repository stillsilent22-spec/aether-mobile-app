package io.aether.wrapper

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Verwaltet das Yggdrasil-Binary gemäß AETHER-Sicherheitsrichtlinien.
 * Binaries werden aus Assets geladen, NIEMALS heruntergeladen.
 */
class YggdrasilManager(private val context: Context) {

    // SHA-256 Hashes der Binaries (Platzhalter - müssen mit echten Hashen gefüllt werden)
    private val expectedHashes = mapOf(
        "arm64-v8a"      to "840ec903afc5c858063cf27abe88e2b09de4f2fa8ec1444aadbcf5cc9fba0fb6",
        "armeabi-v7a"      to "463f07a69fdb8f7feafeb2126c17b0a02e27ff226e77058476e60ff6e6ff599f",
        "x86_64"      to "15331b45cad63c1b2af8e76d8a908f69052c4ee80d40abb0c4c0a052d984a1f1"
    )

    private val binaryFile = File(context.filesDir, "yggdrasil")
    private val configFile = File(context.filesDir, "yggdrasil.conf")

    fun ensureInstalled(): Boolean {
        if (binaryFile.exists() && binaryFile.length() > 0L && verifyHash()) return true
        val abi = selectAbi() ?: return false
        val assetPath = "yggdrasil/$abi/yggdrasil"
        return try {
            context.assets.open(assetPath).use { input ->
                binaryFile.outputStream().use { output -> input.copyTo(output) }
            }
            binaryFile.setExecutable(true, true)
            verifyHash()
        } catch (e: Exception) {
            false
        }
    }

    private fun verifyHash(): Boolean {
        val abi = selectAbi() ?: return false
        val expected = expectedHashes[abi] ?: return false
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(binaryFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        return actual == expected
    }

    private fun selectAbi(): String? {
        val supported = Build.SUPPORTED_ABIS.toList()
        return listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            .firstOrNull { it in supported }
    }

    fun generateConfig(nodeId: String): File {
        val seed = MessageDigest.getInstance("SHA-256").digest(nodeId.toByteArray())
        val configJson = """
            {
              "Peers": [],
              "Listen": [],
              "AdminListen": "none",
              "PrivateKey": "${seed.joinToString("") { "%02x".format(it) }}",
              "IfName": "none",
              "IfMTU": 65535
            }
        """.trimIndent()
        configFile.writeText(configJson)
        return configFile
    }

    fun start(nodeId: String): Process? {
        if (!ensureInstalled()) return null
        val config = generateConfig(nodeId)
        return try {
            ProcessBuilder(binaryFile.absolutePath, "-useconffile", config.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            null
        }
    }
}
