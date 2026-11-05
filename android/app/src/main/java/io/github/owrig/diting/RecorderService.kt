package io.github.owrig.diting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 前台服务：保持蓝牙连接，把音频写成文件。App 切到后台、锁屏后也继续工作。 */
class RecorderService : Service() {

    private lateinit var ble: BleLink
    private lateinit var chunks: ChunkRecorder
    private val io = Executors.newSingleThreadScheduledExecutor()   // 所有文件操作都在这个线程
    private var wakeLock: PowerManager.WakeLock? = null
    private var bytesThisSecond = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        chunks = ChunkRecorder(chunkDir(this))
        ble = BleLink(
            this,
            onAudio = { seq, opus ->
                io.execute {
                    chunks.onPacket(seq, opus)
                    bytesThisSecond += opus.size
                }
            },
            onBattery = { mv -> AppState.update { it.copy(batteryMv = mv) } },
            onState = { state, info ->
                if (state != LinkState.STREAMING) io.execute { chunks.closeChunk(); chunks.resetSequence() }
                AppState.update {
                    it.copy(link = state,
                        deviceName = if (state == LinkState.STREAMING) info else it.deviceName,
                        message = if (state != LinkState.STREAMING) info else null)
                }
                updateNotification()
            },
        )
        // 每秒刷新一次统计
        io.scheduleAtFixedRate({
            val bps = bytesThisSecond
            bytesThisSecond = 0
            AppState.update {
                it.copy(totalPackets = chunks.totalPackets, lostPackets = chunks.lostPackets,
                    bytesPerSec = bps, currentChunk = chunks.currentFileName,
                    currentChunkSeconds = chunks.currentSeconds)
            }
        }, 1, 1, TimeUnit.SECONDS)
        // 每小时清理一次 7 天前的录音
        io.scheduleAtFixedRate({ Recordings.cleanup(chunkDir(this)) }, 0, 1, TimeUnit.HOURS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diting:recorder").apply { acquire() }
        }
        ble.start()
        return START_STICKY      // 被系统杀掉后尽量自动重启
    }

    override fun onDestroy() {
        ble.stop()
        io.execute { chunks.closeChunk() }
        io.shutdown()
        io.awaitTermination(2, TimeUnit.SECONDS)
        wakeLock?.release()
        wakeLock = null
        AppState.update { it.copy(link = LinkState.STOPPED) }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "录音状态", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val s = AppState.status.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val text = buildString {
            append(s.link.label)
            s.batteryMv?.let { append(" · 电池 %.2fV".format(it / 1000.0)) }
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("谛听")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    companion object {
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "recorder"
        private const val NOTIF_ID = 1
        fun chunkDir(ctx: Context) = File(ctx.filesDir, "chunks")
    }
}
