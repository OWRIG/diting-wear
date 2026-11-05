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
import androidx.compose.ui.text.style.TextOverflow
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
    private val uploader by lazy { Uploader(this) }

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
                recs = Recordings.list(this@MainActivity)
                whitelisted = isBatteryWhitelisted()
                delay(3000)
            }
        }
        LaunchedEffect(Unit) { while (true) { player.tick(); delay(200) } }
        // App 在前台时每 30 秒取一次文字（录音服务在跑时它也会每分钟取）
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Transcripts.pull(this@MainActivity) }
                delay(30_000)
            }
        }

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
                val cfg = Config.load(this@MainActivity)
                Text(
                    if (cfg == null) "未配置服务器，不会上传"
                    else if (s.pendingUploads > 0) "待上传 ${s.pendingUploads} 段（每分钟自动重试）"
                    else "已全部上传",
                    style = MaterialTheme.typography.bodySmall)
                if (s.currentChunk != null && s.link == LinkState.STREAMING) {
                    val start = Recordings.parseStart(java.io.File(s.currentChunk))
                    Text("当前片段 ${start?.format(DT) ?: ""} 起，已录 ${fmtDuration(s.currentChunkSeconds)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun RecordingList(recs: List<Recording>) {
        val byDay = recs.groupBy { it.start.toLocalDate() }
        var expanded by remember { mutableStateOf<String?>(null) }
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
                    val tr = Transcripts.get(this@MainActivity, r.name)
                    var wave by remember(r.file.name) { mutableStateOf(Waveforms.cached(r.file)) }
                    LaunchedEffect(r.file.name) { if (wave == null && r.hasAudio) wave = Waveforms.get(r.file) }
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surface)
                            .clickable { if (r.hasAudio) player.open(r) else expanded = if (expanded == r.name) null else r.name }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.start.format(DT), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(fmtDuration(r.durationSec), color = MaterialTheme.colorScheme.outline)
                        }
                        Text("至 ${r.end.format(DT)} · ${statusLabel(r, tr)}",
                            color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                        if (r.hasAudio) {
                            Spacer(Modifier.height(6.dp))
                            Waveform(wave, Modifier.fillMaxWidth().height(24.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                        }
                        if (tr != null && tr.text.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            if (expanded == r.name) {
                                tr.segments.forEach { seg ->
                                    Text("${r.start.plusSeconds(seg.start.toLong()).format(T)}  ${seg.text}",
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                            } else {
                                Text(tr.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
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
                    Column(Modifier.weight(1f)) {
                        Text(r.start.format(DT), style = MaterialTheme.typography.titleSmall)
                        Text("正在播放 " + r.start.plusSeconds((player.positionMs / 1000).toLong()).format(DT),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { player.close() }) { Text("关闭") }
                }
                val dur = player.durationMs.coerceAtLeast(1)
                var wave by remember(r.file.name) { mutableStateOf(Waveforms.cached(r.file)) }
                LaunchedEffect(r.file.name) { if (wave == null) wave = Waveforms.get(r.file) }
                Spacer(Modifier.height(4.dp))
                Waveform(
                    wave,
                    Modifier.fillMaxWidth().height(56.dp),
                    progress = dragging ?: (player.positionMs.toFloat() / dur),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    playedColor = MaterialTheme.colorScheme.primary,
                    onSeekPreview = { dragging = it },
                    onSeek = { player.seekTo((it * dur).toInt()); dragging = null },
                )
                Spacer(Modifier.height(4.dp))
                TranscriptPane(r)
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

    /** 播放栏里的逐句文字：显示每句话的实际时刻，正在播放的那句高亮，点一下跳过去 */
    @Composable
    private fun TranscriptPane(r: Recording) {
        val tr = Transcripts.get(this, r.name) ?: return
        if (tr.segments.isEmpty()) return
        val pos = player.positionMs / 1000.0
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val cur = tr.segments.indexOfLast { it.start <= pos + 0.2 }
        LaunchedEffect(cur) { if (cur >= 0) listState.animateScrollToItem(maxOf(0, cur - 1)) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp), state = listState) {
            items(tr.segments.size) { i ->
                val seg = tr.segments[i]
                val active = i == cur
                Row(Modifier.fillMaxWidth().clickable { player.seekTo((seg.start * 1000).toInt()) }
                    .padding(vertical = 4.dp)) {
                    Text(r.start.plusSeconds(seg.start.toLong()).format(T),
                        color = MaterialTheme.colorScheme.outline, fontSize = 12.sp,
                        modifier = Modifier.width(64.dp))
                    Text(seg.text, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }

    private fun statusLabel(r: Recording, tr: Transcript?): String = when {
        tr != null -> "已转写"
        !r.hasAudio -> "录音已过期"
        uploader.isUploaded(r.file) -> "等待转写"
        else -> "待上传"
    }

    companion object {
        private val DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val T = DateTimeFormatter.ofPattern("HH:mm:ss")
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
