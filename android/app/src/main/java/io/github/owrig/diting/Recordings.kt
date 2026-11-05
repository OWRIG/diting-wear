package io.github.owrig.diting

import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** 一段录音：文件名就是开始时间（yyyyMMdd-HHmmss.opus） */
data class Recording(val file: File, val start: LocalDateTime, val durationSec: Double) {
    val end: LocalDateTime get() = start.plusSeconds(durationSec.toLong())
}

object Recordings {
    const val RETENTION_DAYS = 7L
    private val NAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val durationCache = ConcurrentHashMap<String, Double>()

    fun parseStart(f: File): LocalDateTime? =
        runCatching { LocalDateTime.parse(f.name.removeSuffix(".opus"), NAME_FMT) }.getOrNull()

    /** 列出所有已完成的录音，新的在前 */
    fun list(dir: File): List<Recording> =
        (dir.listFiles { f -> f.name.endsWith(".opus") } ?: emptyArray())
            .mapNotNull { f ->
                val start = parseStart(f) ?: return@mapNotNull null
                val dur = durationCache.getOrPut("${f.name}:${f.length()}") { oggDurationSec(f) }
                Recording(f, start, dur)
            }
            .sortedByDescending { it.start }

    /** 删除 RETENTION_DAYS 天之前的录音（按自然日：今天 + 前 6 天保留） */
    fun cleanup(dir: File, today: LocalDate = LocalDate.now()) {
        val keepFrom = today.minusDays(RETENTION_DAYS - 1).atStartOfDay()
        dir.listFiles { f -> f.name.endsWith(".opus") }?.forEach { f ->
            val start = parseStart(f)
            if (start != null && start.isBefore(keepFrom)) f.delete()
        }
    }

    /** 读 Ogg 文件最后一页的 granule position 得到时长（48kHz，去掉 pre-skip） */
    fun oggDurationSec(f: File): Double = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val tail = minOf(len, 65536L).toInt()
            val buf = ByteArray(tail)
            raf.seek(len - tail)
            raf.readFully(buf)
            for (i in tail - 27 downTo 0) {
                if (buf[i] == 'O'.code.toByte() && buf[i + 1] == 'g'.code.toByte() &&
                    buf[i + 2] == 'g'.code.toByte() && buf[i + 3] == 'S'.code.toByte()) {
                    var g = 0L
                    for (k in 7 downTo 0) g = (g shl 8) or (buf[i + 6 + k].toLong() and 0xff)
                    return@use maxOf(0.0, (g - OggOpusWriter.PRE_SKIP) / 48000.0)
                }
            }
            0.0
        }
    }.getOrDefault(0.0)
}
