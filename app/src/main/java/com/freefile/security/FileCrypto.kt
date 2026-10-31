package com.freefile.security

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.security.NoSuchAlgorithmException
import java.security.spec.KeySpec
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 独立文件加解密引擎，与压缩/解压完全解耦。
 *
 * 算法：AES-256-GCM（认证加密，128 位标签）
 * 密钥派生：PBKDF2-HMAC-SHA256，200000 次迭代，16 字节随机盐
 * Nonce：12 字节随机（GCM 推荐）
 *
 * .cry 文件格式（大端）：
 *   Magic       : "CRY1" (4 字节 ASCII)
 *   Version     : 1 字节 (= 1)
 *   SaltLen     : 2 字节 + Salt 字节
 *   NonceLen    : 2 字节 + Nonce 字节
 *   NameLen     : 2 字节 + 原始文件名 UTF-8 字节
 *   OrigSize    : 8 字节 (原始文件大小，long)
 *   Ciphertext  : 密文 + GCM 认证标签(16 字节，由 Cipher 自动追加)
 *
 * 所有方法均为阻塞方法，应在 IO 线程调用。
 */
object FileCrypto {

    private const val MAGIC = "CRY1"
    private const val VERSION: Byte = 1
    private const val PBKDF2_ITERATIONS = 200_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val BUFFER_SIZE = 64 * 1024
    private const val CRY_EXTENSION = ".cry"

    class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** 判断是否为 .cry 加密文件（按扩展名）。 */
    fun isEncryptedFile(name: String): Boolean = name.lowercase().endsWith(CRY_EXTENSION)

    /** 加密后的输出文件：原文件名 + .cry */
    fun encryptedOutputFor(source: File): File = File(source.parentFile, "${source.name}$CRY_EXTENSION")

    /**
     * 加密单个文件。
     * @param input     源文件
     * @param output    输出 .cry 文件
     * @param password  密码（调用方负责在使用后清除）
     * @param onProgress 进度回调 0..100
     */
    fun encrypt(
        input: File,
        output: File,
        password: CharArray,
        onProgress: (Int) -> Unit = {}
    ) {
        require(input.isFile) { "源文件不存在: ${input.absolutePath}" }
        require(password.isNotEmpty()) { "密码不能为空" }

        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val originalName = input.name.toByteArray(Charsets.UTF_8)
        val originalSize = input.length()

        var key: SecretKeySpec? = null
        try {
            key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))

            BufferedOutputStream(FileOutputStream(output)).use { out ->
                // 写入文件头
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.write(VERSION.toInt())
                writeLenBytes(out, salt)
                writeLenBytes(out, nonce)
                writeLenBytes(out, originalName)
                out.write(longToBytes(originalSize))

                // 流式加密
                var processed = 0L
                val buf = ByteArray(BUFFER_SIZE)
                BufferedInputStream(FileInputStream(input)).use { inputStream ->
                    while (true) {
                        val read = inputStream.read(buf)
                        if (read == -1) break
                        val encrypted = cipher.update(buf, 0, read)
                        if (encrypted != null) out.write(encrypted)
                        processed += read
                        onProgress(((processed * 100) / originalSize.coerceAtLeast(1)).toInt().coerceIn(0, 99))
                    }
                    val final = cipher.doFinal()
                    if (final != null) out.write(final)
                }
                onProgress(100)
            }
        } catch (e: Exception) {
            output.delete()
            throw CryptoException("加密失败: ${e.message}", e)
        } finally {
            key?.let { Arrays.fill(it.encoded, 0) }
            Arrays.fill(salt, 0)
            Arrays.fill(nonce, 0)
        }
    }

    /**
     * 解密单个 .cry 文件。
     *
     * 安全策略：先解密到临时文件，GCM 标签校验通过（密码正确且文件完整）后才重命名为最终文件；
     * 若密码错误或文件损坏，删除临时文件并抛出，绝不输出任何明文。
     */
    fun decrypt(
        input: File,
        outputDir: File,
        password: CharArray,
        onProgress: (Int) -> Unit = {}
    ): File {
        require(input.isFile) { "加密文件不存在: ${input.absolutePath}" }
        require(password.isNotEmpty()) { "密码不能为空" }
        outputDir.mkdirs()

        var key: SecretKeySpec? = null
        var tempFile: File? = null
        try {
            BufferedInputStream(FileInputStream(input)).use { inputStream ->
                val magic = ByteArray(4).also { readFully(inputStream, it) }
                require(String(magic, Charsets.US_ASCII) == MAGIC) { "不是有效的 .cry 文件" }
                val version = inputStream.read()
                require(version == VERSION.toInt()) { "不支持的版本号: $version" }

                val salt = readLenBytes(inputStream)
                val nonce = readLenBytes(inputStream)
                val originalNameBytes = readLenBytes(inputStream)
                val originalName = String(originalNameBytes, Charsets.UTF_8).ifBlank {
                    input.name.substringBeforeLast('.', input.name)
                }
                val sizeBytes = ByteArray(8).also { readFully(inputStream, it) }

                key = deriveKey(password, salt)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))

                val outputFile = uniqueOutputFile(outputDir, originalName)
                tempFile = File(outputDir, ".${outputFile.name}.tmp")

                var processed = 0L
                val totalCipher = input.length()
                val buf = ByteArray(BUFFER_SIZE)
                BufferedOutputStream(FileOutputStream(tempFile)).use { out ->
                    while (true) {
                        val read = inputStream.read(buf)
                        if (read == -1) break
                        val decrypted = cipher.update(buf, 0, read)
                        if (decrypted != null) out.write(decrypted)
                        processed += read
                        onProgress(((processed * 100) / totalCipher.coerceAtLeast(1)).toInt().coerceIn(0, 99))
                    }
                    // doFinal 验证 GCM 标签；密码错误/文件损坏时抛 AEADBadTagException
                    val final = cipher.doFinal()
                    if (final != null) out.write(final)
                }

                if (!tempFile!!.renameTo(outputFile)) {
                    tempFile!!.copyTo(outputFile, overwrite = true)
                    tempFile!!.delete()
                }
                onProgress(100)
                return outputFile
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            tempFile?.delete()
            throw CryptoException("密码错误或文件已损坏")
        } catch (e: Exception) {
            tempFile?.delete()
            if (e is CryptoException) throw e
            throw CryptoException("解密失败: ${e.message}", e)
        } finally {
            key?.let { Arrays.fill(it.encoded, 0) }
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec: KeySpec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (e: NoSuchAlgorithmException) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", "BC")
        }
        val keyBytes = factory.generateSecret(spec).encoded
        try {
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            Arrays.fill(keyBytes, 0)
            (spec as? PBEKeySpec)?.clearPassword()
        }
    }

    private fun writeLenBytes(out: OutputStream, data: ByteArray) {
        out.write((data.size shr 8) and 0xFF)
        out.write(data.size and 0xFF)
        out.write(data)
    }

    private fun readLenBytes(input: InputStream): ByteArray {
        val hi = input.read()
        val lo = input.read()
        require(hi >= 0 && lo >= 0) { "文件头不完整" }
        val len = (hi shl 8) or lo
        val data = ByteArray(len)
        readFully(input, data)
        return data
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            require(read >= 0) { "文件意外结束" }
            offset += read
        }
    }

    private fun longToBytes(v: Long): ByteArray = ByteArray(8).also {
        for (i in 7 downTo 0) it[7 - i] = ((v shr (i * 8)) and 0xFF).toByte()
    }

    private fun uniqueOutputFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val f = File(dir, "$base($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }
}
