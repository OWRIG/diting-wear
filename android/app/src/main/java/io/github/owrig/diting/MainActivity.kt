package io.github.owrig.diting

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {

    private var player: MediaPlayer? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) startRecorder()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }

    private fun neededPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun requestStart() = permLauncher.launch(neededPermissions())

    private fun startRecorder() = startForegroundService(Intent(this, RecorderService::class.java))

    private fun stopRecorder() =
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))

    private fun isBatteryWhitelisted() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBatteryWhitelist() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")))
    }

    private fun play(f: File) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            start()
        }
    }

    @Composable
    private fun Screen() {
        val s by AppState.status.collectAsStateWithLifecycle()
        var files by remember { mutableStateOf(listOf<File>()) }
        var whitelisted by remember { mutableStateOf(isBatteryWhitelisted()) }
        LaunchedEffect(Unit) {
            while (true) {
                files = RecorderService.chunkDir(this@MainActivity)
                    .listFiles { f -> f.name.endsWith(".opus") }
                    ?.sortedByDescending { it.name } ?: emptyList()
                whitelisted = isBatteryWhitelisted()
                delay(2000)
            }
        }

        Scaffold { pad ->
            Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
                Text("谛听", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.link.label, style = MaterialTheme.typography.titleMedium)
                        s.deviceName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        s.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.height(8.dp))
                        Text("电池：" + (s.batteryMv?.let { "%.2f V".format(it / 1000.0) } ?: "—"))
                        Text("流量：%.1f KB/s".format(s.bytesPerSec / 1000.0))
                        val lossPct = if (s.totalPackets > 0)
                            s.lostPackets * 100.0 / (s.totalPackets + s.lostPackets) else 0.0
                        Text("收包：${s.totalPackets}  丢包：${s.lostPackets}（%.2f%%）".format(lossPct))
                        s.currentChunk?.let {
                            Text("正在写：$it（%d 秒）".format(s.currentChunkSeconds.toInt()))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.link == LinkState.STOPPED) {
                        Button(onClick = { requestStart() }) { Text("开始录音") }
                    } else {
                        OutlinedButton(onClick = { stopRecorder() }) { Text("停止") }
                    }
                    if (!whitelisted) {
                        OutlinedButton(onClick = { requestBatteryWhitelist() }) { Text("允许后台运行") }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("录音文件（点击播放）", style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(files, key = { it.name }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { play(f) }.padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(f.nameWithoutExtension)
                            Text("%.0f KB".format(f.length() / 1000.0))
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
