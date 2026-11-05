package io.github.owrig.diting

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把录好的 .opus 上传到信箱。上传成功后在 files/uploaded/ 里放一个同名标记文件。
 * 失败了没关系，下一轮会再试（服务器端同名上传是幂等的）。
 */
class Uploader(private val ctx: Context) {
    private val chunkDir = RecorderService.chunkDir(ctx)
    private val markDir = File(ctx.filesDir, "uploaded").apply { mkdirs() }

    private val silentDir = File(ctx.filesDir, "novoice").apply { mkdirs() }

    fun isUploaded(f: File) = File(markDir, f.name).exists()
    /** 这段录音没有人声，没有上传（录音本身照常在手机上保留 7 天） */
    fun isSilent(f: File) = File(silentDir, f.name).exists()

    /** 上传所有还没上传的录音，返回 (成功数, 剩余数) */
    fun uploadPending(): Pair<Int, Int> {
        val cfg = Config.load(ctx) ?: return 0 to pending().size
        var ok = 0
        for (f in pending()) {
            val speech = Vad.speechSeconds(ctx, f)
            if (speech != null && speech < Vad.MIN_SPEECH_S) {
                File(silentDir, f.name).createNewFile()
                Log.i(TAG, "${f.name} 没有人声，不上传")
                continue
            }
            if (upload(cfg, f)) {
                File(markDir, f.name).createNewFile()
                ok++
            } else break                     // 网络不通就别一个个试了，等下一轮
        }
        markDir.listFiles()?.forEach { if (!File(chunkDir, it.name).exists()) it.delete() }
        silentDir.listFiles()?.forEach { if (!File(chunkDir, it.name).exists()) it.delete() }
        return ok to pending().size
    }

    private fun pending() =
        (chunkDir.listFiles { f -> f.name.endsWith(".opus") } ?: emptyArray())
            .filter { !isUploaded(it) && !isSilent(it) }.sortedBy { it.name }

    private fun upload(cfg: Config, f: File): Boolean = runCatching {
        val conn = URL("${cfg.serverUrl}/v1/audio/${f.name}").openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(f.length())
        conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
        conn.setRequestProperty("Content-Type", "audio/ogg")
        f.inputStream().use { input -> conn.outputStream.use { input.copyTo(it) } }
        val code = conn.responseCode
        conn.disconnect()
        if (code != 200) Log.w(TAG, "upload ${f.name} -> HTTP $code")
        code == 200
    }.onFailure { Log.w(TAG, "upload ${f.name} failed: ${it.message}") }.getOrDefault(false)

    companion object { private const val TAG = "Uploader" }
}

/** 服务器地址和 token。默认值在编译时从 .secrets/mailbox.env 注入，也可以在 App 里修改。 */
data class Config(val serverUrl: String, val token: String) {
    companion object {
        fun load(ctx: Context): Config? {
            val p = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
            val url = p.getString("server_url", null) ?: BuildConfig.DEFAULT_SERVER_URL
            val token = p.getString("token", null) ?: BuildConfig.DEFAULT_PHONE_TOKEN
            if (url.isBlank() || token.isBlank()) return null
            return Config(url.trimEnd('/'), token)
        }

        fun save(ctx: Context, url: String, token: String) =
            ctx.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                .putString("server_url", url.trim()).putString("token", token.trim()).apply()
    }
}
