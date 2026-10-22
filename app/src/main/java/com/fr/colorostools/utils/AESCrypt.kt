package com.fr.colorostools.utils

import android.annotation.SuppressLint
import org.lsposed.lsparanoid.Obfuscate
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Obfuscate
object AESCrypt {
    private val cryptName = "AES"

    fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val outputStream = ByteArrayOutputStream(data.size)
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            outputStream.write(buffer, 0, count)
        }
        outputStream.close()
        return outputStream.toByteArray()
    }

    fun decompress(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val outputStream = ByteArrayOutputStream(data.size)
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            outputStream.write(buffer, 0, count)
        }
        outputStream.close()
        return outputStream.toByteArray()
    }

    @SuppressLint("GetInstance")
    fun encrypt(
        data: String, key: String = CommandUtils.aesCryptKey,
        compress: Boolean = false
    ): String {
        //初始化cipher对象
        val cipher = Cipher.getInstance(cryptName)
        // 生成密钥
        val keySpec = SecretKeySpec(key.toByteArray(), cryptName)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        //加密解密
        val bytes = if (compress) compress(data.toByteArray()) else data.toByteArray()
        val encrypt = cipher.doFinal(bytes)
        val result = Base64.getMimeEncoder().encode(encrypt)

        return String(result)
    }

    @SuppressLint("GetInstance")
    fun decrypt(
        data: String, key: String = CommandUtils.aesCryptKey,
        compress: Boolean = false
    ): String {
        //初始化cipher对象
        val cipher = Cipher.getInstance(cryptName)
        // 生成密钥
        val keySpec = SecretKeySpec(key.toByteArray(), cryptName)
        cipher.init(Cipher.DECRYPT_MODE, keySpec)
        //加密解密
        val encrypt = cipher.doFinal(Base64.getMimeDecoder().decode(data.toByteArray()))
        val bytes = if (compress) decompress(encrypt) else encrypt
        //AES解密不需要用Base64解码
        return String(bytes)
    }

    fun baseEntrypt(data: String): String {
        val encrypt = Base64.getMimeEncoder().encode(data.toByteArray())
        return String(encrypt)
    }

    fun baseDetrypt(data: String): String {
        val decrypt = Base64.getMimeDecoder().decode(data.toByteArray())
        return String(decrypt)
    }
}