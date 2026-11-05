package io.github.owrig.diting

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class Segment(val start: Double, val end: Double, val text: String, val speaker: String? = null)
data class Transcript(val name: String, val durationSec: Double, val text: String, val segments: List<Segment>)

/**
 * 转写文字：从信箱取回后存在 files/texts/<录音名>.json。
 * 文字不跟着录音一起删除（录音 7 天后删，文字一直保留）。
 */
object Transcripts {
    private const val TAG = "Transcripts"
    private val mem = ConcurrentHashMap<String, Transcript>()

    fun dir(ctx: Context) = File(ctx.filesDir, "texts").apply { mkdirs() }

    /** 某段录音（文件名 xxx.opus）对应的文字，没有就返回 null */
    fun get(ctx: Context, name: String): Transcript? = mem[name] ?: File(dir(ctx), "$name.json")
        .takeIf { it.exists() }?.let { runCatching { parse(JSONObject(it.readText())) }.getOrNull() }
        ?.also { mem[name] = it }

    fun allNames(ctx: Context): List<String> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.map { it.name.removeSuffix(".json") } ?: emptyList()

    /** 从信箱取回新的转写结果，返回新取到的条数 */
    fun pull(ctx: Context): Int {
        val cfg = Config.load(ctx) ?: return 0
        val since = allNames(ctx).maxOrNull() ?: ""
        return runCatching {
            val url = URL("${cfg.serverUrl}/v1/text?since=${URLEncoder.encode(since, "UTF-8")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val items = JSONObject(body).getJSONArray("items")
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val name = o.getString("name")
                File(dir(ctx), "$name.json").writeText(o.toString())
                mem.remove(name)
            }
            items.length()
        }.onFailure { Log.w(TAG, "pull failed: ${it.message}") }.getOrDefault(0)
    }

    private fun parse(o: JSONObject): Transcript {
        val segs = o.optJSONArray("segments")
        val list = (0 until (segs?.length() ?: 0)).map {
            val s = segs!!.getJSONObject(it)
            Segment(s.optDouble("start"), s.optDouble("end"), s.optString("text"),
                s.optString("speaker").takeIf { it.isNotBlank() })
        }
        return Transcript(o.getString("name"), o.optDouble("duration_s", 0.0), o.optString("text"), list)
    }
}
