package com.jknl.englishreader.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The key never enters shared preferences, backups, exported books, or logs. */
class ApiKeyStore(context: Context, private val alias: String = "englishreader.deepseek") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$alias.enc"))
    fun exists() = file.baseFile.exists()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): String {
        if (!exists()) return ""
        try {
            val o = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(o.getString("iv"), Base64.NO_WRAP)))
            return String(cipher.doFinal(Base64.decode(o.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { error("密钥无法解密，请在设置中重新填写。") }
    }
    fun save(value: String) {
        val cleaned = value.trim()
        require(cleaned.length in 8..512 && cleaned.all { it.code in 33..126 }) { "请填写完整的 API Key，不要包含空格或换行。" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val raw = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(cleaned.toByteArray()), Base64.NO_WRAP)).toString()
        val stream = file.startWrite()
        try { stream.write(raw.toByteArray()); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() { file.delete() }
}

class DeepSeekSettings(context: Context, name: String = "deepseek-settings") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    var model: String
        get() = prefs.getString("model", "deepseek-flash") ?: "deepseek-flash"
        set(value) { require(value.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "模型名称格式无效" }; prefs.edit().putString("model", value).apply() }
    var goal: LearningGoal
        get() = runCatching { LearningGoal.valueOf(prefs.getString("goal", LearningGoal.KAOYAN.name)!!) }.getOrDefault(LearningGoal.KAOYAN)
        set(value) { prefs.edit().putString("goal", value.name).apply() }
}

fun interface WordTransport { suspend fun complete(key: String, body: String): String }

object DeepSeekTransport : WordTransport {
    private val workers = Executors.newCachedThreadPool()
    override suspend fun complete(key: String, body: String): String = request(key, body, 60_000, 256 * 1024)
    /** Web search with thinking can run many server-side steps; allow minutes and a larger reply. */
    val slow = WordTransport { key, body -> request(key, body, 240_000, 2 * 1024 * 1024) }
    private suspend fun request(key: String, body: String, readTimeout: Int, limit: Int): String = suspendCancellableCoroutine { continuation ->
        val connection = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
        continuation.invokeOnCancellation { connection.disconnect() }
        workers.execute {
            try {
                if (!continuation.isActive) return@execute
                connection.apply {
                    requestMethod = "POST"; connectTimeout = 15000; this.readTimeout = readTimeout
                    instanceFollowRedirects = false; doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                require(status in 200..299) { WordPrompt.httpError(status) }
                val result = connection.inputStream.use { it.readLimited(limit).toString(Charsets.UTF_8) }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) {
                val safe = when (e) {
                    is SocketTimeoutException -> IllegalStateException("请求超时，稍后点重试。")
                    is IllegalArgumentException -> e
                    else -> IllegalStateException("无法连接 DeepSeek，请检查网络后重试。")
                }
                if (continuation.isActive) continuation.resumeWithException(safe)
            } finally { connection.disconnect() }
        }
    }
}

data class WordResult(val explanation: WordExplanation, val cached: Boolean, val record: WordRecord? = null)

class WordRepository(context: Context, private val transport: WordTransport = DeepSeekTransport,
    private val keys: ApiKeyStore = ApiKeyStore(context), private val config: DeepSeekSettings = DeepSeekSettings(context),
    private val notebook: WordNotebook = WordNotebook(context)) {
    private val directory = File(context.cacheDir, "word-lookups").apply { mkdirs() }
    suspend fun lookup(query: WordQuery, origin: WordOrigin = WordOrigin(), forceRefresh: Boolean = false,
        recordHistory: Boolean = true): WordResult = withContext(Dispatchers.IO) {
        val model = config.model
        val goal = config.goal
        val cacheKey = contentId(WordPrompt.VERSION + model + goal.name + query.json().toString())
        val file = File(directory, "$cacheKey.json")
        fun result(raw: String, cached: Boolean): WordResult {
            val explanation = WordExplanation.parse(raw)
            val record = WordRecord.create(cacheKey, query, origin, raw, model, goal)
            if (recordHistory) notebook.recordRecent(record)
            return WordResult(explanation, cached, record)
        }
        if (!forceRefresh) {
            notebook.findResponse(cacheKey)?.let { return@withContext result(it, true) }
            if (file.exists()) runCatching { file.readText().also { WordExplanation.parse(it) } }.getOrNull()?.let { return@withContext result(it, true) }
        }
        val key = keys.read()
        require(key.isNotBlank()) { "先在「阅读设置 → DeepSeek 查词」填写 API Key。" }
        val raw = WordPrompt.complete(query, model, goal) { body -> transport.complete(key, body) }
        // Cache failures must not hide a successful answer.
        runCatching {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(raw.toByteArray()); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(250)?.forEach { it.delete() }
        }
        result(raw, false)
    }
}
