package io.github.owrig.diting

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.math.abs

/** 一个地点：第一次在这里定位时自动创建，用户可以起名字、和别的地点合并 */
data class Place(
    val id: Int,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val mergedInto: Int?,
) {
    val defaultName: String get() = "未命名地点 #$id"
    val label: String get() = name ?: defaultName
}

/** 一次定位记录 */
data class Fix(val t: Long, val lat: Double, val lon: Double, val acc: Float, val place: Int)

/**
 * 地点：录音时每 5 分钟记一次手机位置，200 米以内算同一个地方。
 * 位置只存在手机上（files/places/），不上传。
 */
object Places {
    private const val TAG = "Places"
    const val SAME_PLACE_M = 200.0
    private const val MAX_ACCURACY_M = 1000f      // 误差比这还大的定位不要
    private const val MATCH_WINDOW_MS = 15 * 60_000L   // 找某个时刻的位置时，前后 15 分钟内的定位都算

    private val lock = Any()
    @Volatile private var places: List<Place>? = null
    @Volatile private var fixes: List<Fix>? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "places").apply { mkdirs() }
    private fun placesFile(ctx: Context) = File(dir(ctx), "places.json")
    private fun fixesFile(ctx: Context) = File(dir(ctx), "fixes.jsonl")

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun all(ctx: Context): List<Place> = synchronized(lock) {
        places ?: runCatching {
            val f = placesFile(ctx)
            if (!f.exists()) emptyList() else JSONArray(f.readText()).let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                    Place(o.getInt("id"), o.optString("name").takeIf { !o.isNull("name") && it.isNotBlank() },
                        o.getDouble("lat"), o.getDouble("lon"),
                        if (o.isNull("merged_into")) null else o.optInt("merged_into"))
                } }
            }
        }.getOrDefault(emptyList()).also { places = it }
    }

    private fun save(ctx: Context, list: List<Place>) {
        val a = JSONArray()
        list.forEach { p -> a.put(JSONObject().apply {
            put("id", p.id); put("name", p.name ?: JSONObject.NULL)
            put("lat", p.lat); put("lon", p.lon); put("merged_into", p.mergedInto ?: JSONObject.NULL)
        }) }
        val tmp = File(dir(ctx), "places.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(placesFile(ctx))
        places = list
    }

    fun fixes(ctx: Context): List<Fix> = synchronized(lock) {
        fixes ?: runCatching {
            val f = fixesFile(ctx)
            if (!f.exists()) emptyList() else f.readLines().mapNotNull { l ->
                runCatching { JSONObject(l).let {
                    Fix(it.getLong("t"), it.getDouble("lat"), it.getDouble("lon"), it.getDouble("acc").toFloat(), it.getInt("place"))
                } }.getOrNull()
            }
        }.getOrDefault(emptyList()).also { fixes = it }
    }

    /** 沿合并链找到最终的地点 */
    fun resolve(ctx: Context, id: Int?): Place? {
        val m = all(ctx).associateBy { it.id }
        var cur = id?.let { m[it] } ?: return null
        val seen = HashSet<Int>()
        while (cur.mergedInto != null && seen.add(cur.id)) cur = m[cur.mergedInto] ?: break
        return cur
    }

    /** 某个时刻在哪儿：取时间最接近的一次定位 */
    fun at(ctx: Context, time: LocalDateTime): Place? {
        val ms = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val f = fixes(ctx).minByOrNull { abs(it.t - ms) } ?: return null
        if (abs(f.t - ms) > MATCH_WINDOW_MS) return null
        return resolve(ctx, f.place)
    }

    /** 记下一次定位，归到 200 米内最近的地点；没有就新建一个 */
    fun record(ctx: Context, loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        synchronized(lock) {
            val list = all(ctx)
            val near = list.map { it to distance(it.lat, it.lon, loc.latitude, loc.longitude) }
                .filter { it.second <= SAME_PLACE_M }.minByOrNull { it.second }?.first
            val placeId = near?.id ?: run {
                val id = (list.maxOfOrNull { it.id } ?: 0) + 1
                save(ctx, list + Place(id, null, loc.latitude, loc.longitude, null))
                id
            }
            val fix = Fix(loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 0f, placeId)
            fixesFile(ctx).appendText(JSONObject().apply {
                put("t", fix.t); put("lat", fix.lat); put("lon", fix.lon); put("acc", fix.acc.toDouble()); put("place", fix.place)
            }.toString() + "\n")
            fixes = fixes(ctx) + fix
        }
    }

    fun rename(ctx: Context, id: Int, name: String) = synchronized(lock) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(name = name.trim().ifBlank { null }) else it })
    }

    fun merge(ctx: Context, id: Int, into: Int?) = synchronized(lock) {
        // 不能合并成环
        var cur = into
        val seen = HashSet<Int>()
        while (cur != null && seen.add(cur)) {
            if (cur == id) return@synchronized
            cur = all(ctx).firstOrNull { it.id == cur }?.mergedInto
        }
        save(ctx, all(ctx).map { if (it.id == id) it.copy(mergedInto = into) else it })
    }

    /** 每个地点（合并后）去过的天数、记录到的时长（分钟，按定位次数 × 5 分钟估算）、最近一次 */
    data class Stats(val minutes: Int, val days: Int, val last: LocalDateTime?)

    fun stats(ctx: Context): Map<Int, Stats> {
        val byPlace = fixes(ctx).groupBy { resolve(ctx, it.place)?.id ?: it.place }
        return byPlace.mapValues { (_, fs) ->
            val times = fs.map { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.t), ZoneId.systemDefault()) }
            Stats(fs.size * 5, times.map { it.toLocalDate() }.distinct().size, times.maxOrNull())
        }
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0].toDouble()
    }

    private val exec = Executors.newSingleThreadExecutor()

    /** 取一次当前位置（异步），取到就记下来。没权限或者定位关了就什么都不做 */
    @SuppressLint("MissingPermission")
    fun requestFix(ctx: Context) {
        if (!hasPermission(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) &&
                lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> { Log.i(TAG, "定位已关闭"); return }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, exec) { loc ->
                    (loc ?: lm.getLastKnownLocation(provider))?.let { record(ctx, it) }
                }
            } else {
                lm.getLastKnownLocation(provider)?.let { exec.execute { record(ctx, it) } }
            }
        }.onFailure { Log.w(TAG, "定位失败: ${it.message}") }
    }
}
