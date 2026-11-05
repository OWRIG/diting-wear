package io.github.owrig.diting

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val player = Player()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) startRecorder()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Recordings.cleanup(RecorderService.chunkDir(this))
        setContent { MaterialTheme { Screen() } }
    }

    override fun onDestroy() {
        player.close()
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
    private fun requestBatteryWhitelist() = startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))

    @Composable
    private fun Screen() {
        val s by AppState.status.collectAsStateWithLifecycle()
        var recs by remember { mutableStateOf(listOf<Recording>()) }
        var whitelisted by remember { mutableStateOf(isBatteryWhitelisted()) }
        LaunchedEffect(Unit) {
            while (true) {
                recs = Recordings.list(RecorderService.chunkDir(this@MainActivity))
                whitelisted = isBatteryWhitelisted()
                delay(3000)
            }
        }
        LaunchedEffect(Unit) { while (true) { player.tick(); delay(200) } }

        Scaffold(bottomBar = { if (player.current != null) PlayerBar() }) { pad ->
            Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
                Spacer(Modifier.height(12.dp))
                Text("谛听", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                StatusCard(s)
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
                Spacer(Modifier.height(8.dp))
                RecordingList(recs)
            }
        }
    }

    @Composable
    private fun StatusCard(s: Status) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(s.link.label, style = MaterialTheme.typography.titleMedium)
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(6.dp))
                val batt = s.batteryMv?.let { "%.2f V".format(it / 1000.0) } ?: "—"
                Text("电池 $batt  ·  %.1f KB/s".format(s.bytesPerSec / 1000.0),
                    style = MaterialTheme.typography.bodyMedium)
                val lossPct = if (s.totalPackets > 0)
                    s.lostPackets * 100.0 / (s.totalPackets + s.lostPackets) else 0.0
                Text("丢包 %.2f%%".format(lossPct), style = MaterialTheme.typography.bodySmall)
                if (s.currentChunk != null && s.link == LinkState.STREAMING) {
                    val start = Recordings.parseStart(java.io.File(s.currentChunk))
                    Text("当前片段 ${start?.format(HM) ?: ""} 起，已录 ${fmtDuration(s.currentChunkSeconds)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun RecordingList(recs: List<Recording>) {
        val byDay = recs.groupBy { it.start.toLocalDate() }
        LazyColumn(Modifier.fillMaxWidth()) {
            if (recs.isEmpty()) item {
                Text("还没有录音", Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.outline)
            }
            byDay.forEach { (day, list) ->
                item(key = "h$day") {
                    val total = list.sumOf { it.durationSec }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(dayLabel(day), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold)
                        Text("共 ${fmtDuration(total)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
                items(list, key = { it.file.name }) { r ->
                    val selected = player.current?.file == r.file
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surface)
                            .clickable { player.open(r) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${r.start.format(HM)} – ${r.end.format(HM)}", fontSize = 16.sp)
                        Text(fmtDuration(r.durationSec), color = MaterialTheme.colorScheme.outline)
                    }
                    HorizontalDivider()
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    @Composable
    private fun PlayerBar() {
        val r = player.current ?: return
        var dragging by remember { mutableStateOf<Float?>(null) }
        Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp, 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${dayLabel(r.start.toLocalDate())} ${r.start.format(HM)} – ${r.end.format(HM)}",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { player.close() }) { Text("关闭") }
                }
                val dur = player.durationMs.coerceAtLeast(1)
                Slider(
                    value = dragging ?: (player.positionMs.toFloat() / dur),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { player.seekTo((it * dur).toInt()) }; dragging = null },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val shown = dragging?.let { (it * dur).toInt() } ?: player.positionMs
                    Text(fmtMs(shown), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { player.skip(-15_000) }) { Text("−15秒") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { player.toggle() }, Modifier.width(88.dp)) {
                        Text(if (player.playing) "暂停" else "播放")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { player.skip(15_000) }) { Text("+15秒") }
                    Spacer(Modifier.weight(1f))
                    Text(fmtMs(player.durationMs), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    companion object {
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        private val MD = DateTimeFormatter.ofPattern("M月d日")

        fun dayLabel(d: LocalDate): String {
            val today = LocalDate.now()
            val week = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
            return when (d) {
                today -> "今天"
                today.minusDays(1) -> "昨天"
                else -> "${d.format(MD)} $week"
            }
        }

        fun fmtDuration(sec: Double): String {
            val s = sec.toLong()
            return when {
                s >= 3600 -> "${s / 3600}小时${(s % 3600) / 60}分"
                s >= 60 -> "${s / 60}分${s % 60}秒"
                else -> "${s}秒"
            }
        }

        fun fmtMs(ms: Int): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
