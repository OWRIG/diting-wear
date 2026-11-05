package io.github.owrig.diting

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把连续到来的 Opus 包按 5 分钟一段切成文件。
 * 文件先写成 .part，写完改名成 .opus，所以列表里看到的 .opus 都是完整的。
 */
class ChunkRecorder(private val dir: File) {

    private var writer: OggOpusWriter? = null
    private var partFile: File? = null
    private var lastSeq = -1
    var lostPackets = 0L
        private set
    var totalPackets = 0L
        private set

    init {
        dir.mkdirs()
        // 上次异常退出留下的半截文件：Ogg 是流式格式，前面的部分仍然能播放，直接转正
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { finalize(it) }
    }

    val currentFileName: String? get() = partFile?.name?.removeSuffix(".part")
    val currentSeconds: Double get() = writer?.durationSeconds ?: 0.0

    @Synchronized
    fun onPacket(seq: Int, opus: ByteArray) {
        if (lastSeq >= 0) {
            val gap = (seq - lastSeq - 1) and 0xFFFF
            if (gap in 1..MAX_FILL_GAP) {
                ensureWriter()
                repeat(gap) { writer!!.writePacket(OggOpusWriter.SILENT_PACKET) }
                lostPackets += gap
            } else if (gap > MAX_FILL_GAP) {
                // 断得太久（或者设备重启了），另起一段
                closeChunk()
            }
        }
        lastSeq = seq
        totalPackets++
        ensureWriter()
        writer!!.writePacket(opus)
        if (writer!!.packetCount >= CHUNK_PACKETS) closeChunk()
    }

    /** 断开连接时调用：收尾当前文件 */
    @Synchronized
    fun closeChunk() {
        writer?.close()
        partFile?.let { finalize(it) }
        writer = null
        partFile = null
    }

    @Synchronized
    fun resetSequence() {
        lastSeq = -1
    }

    private fun ensureWriter() {
        if (writer != null) return
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "$name.opus.part")
        partFile = f
        writer = OggOpusWriter(f)
    }

    private fun finalize(part: File) {
        val dest = File(part.parentFile, part.name.removeSuffix(".part"))
        part.renameTo(dest)
    }

    companion object {
        const val CHUNK_PACKETS = 5 * 60 * 50       // 5 分钟 × 每秒 50 帧
        const val MAX_FILL_GAP = 50                 // 1 秒以内的丢包用 PLC 补
    }
}
