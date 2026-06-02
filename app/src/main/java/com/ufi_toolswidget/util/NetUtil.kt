package com.ufi_toolswidget.util

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object NetUtil {
    private val cookieStore = mutableMapOf<String, List<Cookie>>()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieStore[url.host] = cookies
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            }).build()
    }

    const val BASE_URL = "http://192.168.0.1:2333"
    private const val SECRET_KEY = "minikano_kOyXz0Ciz4V7wR0IeKmJFYFQ20jd"

    fun saveCookies(cookies: List<Cookie>) {
        cookieStore["192.168.0.1"] = cookies
    }

    // SHA256 字符串哈希 (返回十六进制字符串)
    fun sha256(input: String): String {
        return hashBytesToHex(input.toByteArray(), "SHA-256")
    }

    // 内部通用哈希：字节 -> 十六进制字符串
    private fun hashBytesToHex(input: ByteArray, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        val hash = digest.digest(input)
        return hash.joinToString("") { "%02x".format(it) }
    }

    // 内部通用哈希：字节 -> 原始字节
    private fun hashBytesToBytes(input: ByteArray, algorithm: String): ByteArray {
        val digest = MessageDigest.getInstance(algorithm)
        return digest.digest(input)
    }

    // HMAC-MD5 运算
    private fun hmacMd5(data: String, key: String): ByteArray {
        val secretKey = SecretKeySpec(key.toByteArray(), "HmacMD5")
        val mac = Mac.getInstance("HmacMD5")
        mac.init(secretKey)
        return mac.doFinal(data.toByteArray())
    }

    /**
     * 作者文档 v3.1.5 签名核心逻辑
     * kano-sign = SHA256( SHA256(part1) + SHA256(part2) )
     * 注意：这里的 '+' 是字节数组的拼接，不是字符串拼接
     */
    fun generateKanoSign(method: String, path: String, timestamp: Long): String {
        val rawData = "minikano${method.uppercase()}$path$timestamp"
        val hmac = hmacMd5(rawData, SECRET_KEY)
        
        // 1. HMAC 二分为 part1 和 part2 (各 8 字节)
        val part1 = hmac.sliceArray(0 until 8)
        val part2 = hmac.sliceArray(8 until 16)
        
        // 2. 分别计算 SHA256 原始字节
        val sha1 = hashBytesToBytes(part1, "SHA-256")
        val sha2 = hashBytesToBytes(part2, "SHA-256")
        
        // 3. 拼接字节数组并进行最后的 SHA256，返回十六进制字符串
        return hashBytesToHex(sha1 + sha2, "SHA-256")
    }
}
