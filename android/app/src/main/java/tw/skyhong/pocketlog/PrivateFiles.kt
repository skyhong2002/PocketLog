package tw.skyhong.pocketlog

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Platform AES-GCM; only ciphertext is written, including checkpoints and cached timelines. */
interface JournalFiles {
    fun names(prefix: String): List<String>
    fun size(name: String): Long
    fun write(name: String, clear: ByteArray)
    fun read(name: String): ByteArray?
    fun remove(name: String)
}
class PrivateFiles(context: Context) : JournalFiles {
    private val directory = File(context.noBackupFilesDir, "private-v1").apply { mkdirs() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("pocketlog.files.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("pocketlog.files.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun names(prefix: String): List<String> = directory.listFiles()?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".enc") }
        ?.map { it.name.removeSuffix(".enc") }?.sorted().orEmpty()
    fun modifiedAt(name: String): Long = File(directory, "$name.enc").lastModified()
    override fun size(name: String): Long = File(directory, "$name.enc").length()
    override fun write(name: String, clear: ByteArray) {
        require(Regex("[a-zA-Z0-9_-]{1,160}").matches(name))
        require(clear.size <= 600 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(name.toByteArray())
        val encrypted = cipher.doFinal(clear)
        val file = AtomicFile(File(directory, "$name.enc"))
        val output = file.startWrite()
        try {
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }
    override fun read(name: String): ByteArray? {
        val file = AtomicFile(File(directory, "$name.enc"))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        require(file.baseFile.length() <= 600 * 1024 + 64)
        val data = file.openRead().use { it.readNBytes(600 * 1024 + 65) }
        require(data.size <= 600 * 1024 + 64)
        require(data.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        }
        cipher.updateAAD(name.toByteArray())
        return cipher.doFinal(data, 12, data.size - 12)
    }
    override fun remove(name: String) { AtomicFile(File(directory, "$name.enc")).delete() }
    fun clear() { directory.listFiles()?.forEach { it.delete() } }
}
