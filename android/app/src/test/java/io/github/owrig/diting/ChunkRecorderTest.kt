package io.github.owrig.diting

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ChunkRecorderTest {
    private val audio = byteArrayOf(0x48, 1, 2, 3)

    @Test fun silenceDeclarationsFillTimelineAndLossIsCountedSeparately() {
        val dir = Files.createTempDirectory("chunks").toFile()
        val c = ChunkRecorder(dir)
        c.onPacket(29, ByteArray(0))            // 第一次声明：这一帧是安静
        c.onPacket(79, ByteArray(0))            // 1 秒后：30..79 都是安静
        for (s in 80..99) c.onPacket(s, audio)  // 开口（含补发的缓存）
        c.onPacket(101, audio)                  // 100 丢了
        c.onPacket(90, ByteArray(0))            // 旧序号的声明，忽略
        c.closeChunk()
        assertEquals(51, c.silentFrames)
        assertEquals(1, c.lostPackets)
        assertEquals(21, c.totalPackets)
        assertEquals(1, dir.listFiles { f -> f.name.endsWith(".opus") }!!.size)
    }

    @Test fun allSilentChunkIsDeleted() {
        val dir = Files.createTempDirectory("chunks").toFile()
        val c = ChunkRecorder(dir)
        for (k in 1..10) c.onPacket(k * 50, ByteArray(0))
        c.closeChunk()
        assertEquals(0, dir.listFiles()!!.count { it.name.endsWith(".opus") || it.name.endsWith(".part") })
    }

    @Test fun seqWrapAround() {
        val dir = Files.createTempDirectory("chunks").toFile()
        val c = ChunkRecorder(dir)
        c.onPacket(65530, audio)
        c.onPacket(20, ByteArray(0))            // 跨过 65535 回到 0
        c.onPacket(21, audio)
        assertEquals(26, c.silentFrames)    // 65531..65535 共 5 帧 + 0..20 共 21 帧
        assertEquals(0, c.lostPackets)
    }
}
