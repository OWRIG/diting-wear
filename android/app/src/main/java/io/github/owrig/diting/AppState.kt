package io.github.owrig.diting

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class LinkState(val label: String) {
    STOPPED("未启动"),
    SCANNING("正在寻找设备…"),
    CONNECTING("正在连接…"),
    STREAMING("录音中"),
    RECONNECTING("连接断开，等待重连…"),
}

data class Status(
    val link: LinkState = LinkState.STOPPED,
    val deviceName: String? = null,
    val batteryMv: Int? = null,
    val totalPackets: Long = 0,
    val lostPackets: Long = 0,
    val bytesPerSec: Int = 0,
    val currentChunk: String? = null,
    val currentChunkSeconds: Double = 0.0,
    val message: String? = null,
    val pendingUploads: Int = 0,
    val uploadedThisSession: Int = 0,
)

/** 服务和界面之间共享的状态 */
object AppState {
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun update(f: (Status) -> Status) = _status.update(f)
}
