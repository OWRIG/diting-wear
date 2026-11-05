package io.github.owrig.diting

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Speaker(
    val id: String,
    val name: String?,
    val mergedInto: String?,
    val firstSeen: String?,
    val sample: String?,
) {
    /** spk_007 → "未命名 #7" */
    val defaultName: String get() = "未命名 #" + id.removePrefix("spk_").trimStart('0')
}

/** 说话人名单（编号、名字、合并关系）。声纹不在手机上，只在家里的 Mac 上。 */
object Speakers {
    private const val TAG = "Speakers"
    @Volatile private var cache: Map<String, Speaker> = emptyMap()

    private fun file(ctx: Context) = File(ctx.filesDir, "speakers.json")

    fun all(ctx: Context): Map<String, Speaker> {
        if (cache.isEmpty() && file(ctx).exists()) cache = parse(file(ctx).readText())
        return cache
    }

    /** 沿合并链找到最终的人 */
    fun resolve(ctx: Context, id: String?): Speaker? {
        val m = all(ctx)
        var cur = id?.let { m[it] } ?: return null
        val seen = HashSet<String>()
        while (cur.mergedInto != null && seen.add(cur.id)) cur = m[cur.mergedInto] ?: break
        return cur
    }

    fun displayName(ctx: Context, id: String?): String? =
        id?.let { resolve(ctx, it)?.let { s -> s.name ?: s.defaultName } ?: it }

    fun pull(ctx: Context): Boolean {
        val cfg = Config.load(ctx) ?: return false
        return runCatching {
            val conn = URL("${cfg.serverUrl}/v1/speakers").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            file(ctx).writeText(body)
            cache = parse(body)
            true
        }.onFailure { Log.w(TAG, "pull failed: ${it.message}") }.getOrDefault(false)
    }

    /** 起名字或合并（在后台线程调用）。成功后刷新名单。 */
    fun edit(ctx: Context, id: String, name: String? = null, mergeInto: String? = null, unmerge: Boolean = false): Boolean {
        val cfg = Config.load(ctx) ?: return false
        val body = JSONObject().apply {
            if (name != null) put("name", name)
            if (mergeInto != null) put("merged_into", mergeInto)
            if (unmerge) put("merged_into", JSONObject.NULL)
        }.toString()
        return runCatching {
            val conn = URL("${cfg.serverUrl}/v1/speakers/$id").openConnection() as HttpURLConnection
            conn.requestMethod = "PATCH"     // Android 的 HttpURLConnection（OkHttp 实现）支持 PATCH
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${cfg.token}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val ok = conn.responseCode == 200
            conn.disconnect()
            if (ok) pull(ctx)
            ok
        }.onFailure { Log.w(TAG, "edit failed: ${it.message}") }.getOrDefault(false)
    }

    private fun parse(body: String): Map<String, Speaker> {
        val items = JSONObject(body).optJSONObject("items") ?: return emptyMap()
        return items.keys().asSequence().associateWith { id ->
            val o = items.getJSONObject(id)
            Speaker(id, o.optString("name").takeIf { !o.isNull("name") && it.isNotBlank() },
                o.optString("merged_into").takeIf { !o.isNull("merged_into") && it.isNotBlank() },
                o.optString("first_seen").takeIf { it.isNotBlank() },
                o.optString("sample").takeIf { it.isNotBlank() })
        }
    }
}
