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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val player = Player()
    private var speakerVersion by mutableIntStateOf(0)   // 名单变了就让界面刷新
    private var editing by mutableStateOf<Speaker?>(null)    // 正在改名字的人
    private var merging by mutableStateOf<Speaker?>(null)    // 正在合并的人
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
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Transcripts.pull(this@MainActivity); Speakers.pull(this@MainActivity)
                }
                speakerVersion++
                delay(30_000)
            }
        }

        var tab by remember { mutableIntStateOf(0) }
        var showStatus by remember { mutableStateOf(false) }

        Scaffold(bottomBar = { if (player.current != null) PlayerBar() }) { pad ->
            Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("谛听", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    // 一行简要状态，点开看详情
                    val dot = if (s.link == LinkState.STREAMING) "●" else "○"
                    val batt = s.batteryMv?.let { " · 电量 ${batteryPct(it)}%" } ?: ""
                    Text("$dot ${s.link.label}$batt", Modifier.weight(1f).clickable { showStatus = !showStatus },
                        color = if (s.link == LinkState.STREAMING) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodyMedium)
                    if (s.link == LinkState.STOPPED) {
                        Button(onClick = { requestStart() }) { Text("开始录音") }
                    }
                }
                if (showStatus || s.link == LinkState.STOPPED) {
                    Spacer(Modifier.height(8.dp))
                    StatusCard(s)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.link != LinkState.STOPPED) {
                            OutlinedButton(onClick = { stopRecorder() }) { Text("停止录音") }
                        }
                        if (!whitelisted) {
                            OutlinedButton(onClick = { requestBatteryWhitelist() }) { Text("允许后台运行") }
                        }
                    }
                }
                TabRow(selectedTabIndex = tab, Modifier.padding(top = 8.dp)) {
                    Tab(tab == 0, onClick = { tab = 0 }, text = { Text("文字") })
                    Tab(tab == 1, onClick = { tab = 1 }, text = { Text("录音") })
                    Tab(tab == 2, onClick = { tab = 2 }, text = { Text("人物") })
                }
                when (tab) {
                    0 -> TextTimeline(recs)
                    1 -> RecordingList(recs)
                    else -> PeoplePage(recs)
                }
            }
        }
        PersonDialogs(recs)
    }

    /**
     * 纯文字时间线：按天分组（新的一天在上），一天之内按时间顺序。
     * 同一个人、停顿不到 PARA_GAP_S 秒的连续几句合成一个段落，只在段首标时间，读起来更连贯。
     * 点段落里任意一句，从那句开始播放。
     */
    @Composable
    private fun TextTimeline(recs: List<Recording>) {
        @Suppress("UNUSED_EXPRESSION") speakerVersion
        data class Line(val rec: Recording, val seg: Segment) {
            val at: java.time.LocalDateTime = rec.start.plusNanos((seg.start * 1e9).toLong())
            val endAt: java.time.LocalDateTime = rec.start.plusNanos((seg.end * 1e9).toLong())
        }
        class Para(val lines: MutableList<Line>, val who: String?)

        val lines = recs.flatMap { r ->
            Transcripts.get(this, r.name)?.segments?.filter { it.text.isNotBlank() }?.map { Line(r, it) } ?: emptyList()
        }.sortedBy { it.at }
        val paras = ArrayList<Para>()
        for (l in lines) {
            val who = Speakers.displayName(this, l.seg.speaker)
            val last = paras.lastOrNull()
            val gap = last?.let { java.time.Duration.between(it.lines.last().endAt, l.at).toMillis() / 1000.0 }
            if (last != null && last.who == who && gap != null && gap < PARA_GAP_S &&
                last.lines.first().at.toLocalDate() == l.at.toLocalDate()) {
                last.lines.add(l)
            } else {
                paras.add(Para(mutableListOf(l), who))
            }
        }
        val byDay = paras.groupBy { it.lines.first().at.toLocalDate() }.toSortedMap(compareByDescending { it })
        val playingName = player.current?.name
        val pos = player.positionMs / 1000.0

        LazyColumn(Modifier.fillMaxWidth()) {
            if (lines.isNotEmpty()) item {
                Text("点 ▶ 或任意一句话可以听原声；点人名可以改名字",
                    Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
            if (lines.isEmpty()) item {
                Text("还没有转写出来的文字。录音上传后，家里的 Mac 转写完会自动出现在这里。",
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.outline)
            }
            byDay.forEach { (day, dayParas) ->
                item(key = "d$day") {
                    Text(dayLabel(day), Modifier.padding(top = 16.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                items(dayParas.size, key = { "${dayParas[it].lines.first().rec.name}#${dayParas[it].lines.first().seg.start}" }) { i ->
                    val p = dayParas[i]
                    val first = p.lines.first()
                    val prev = dayParas.getOrNull(i - 1)
                    val showWho = p.who != null && p.who != prev?.who
                    // 段落之间隔得久（超过 2 分钟），多留点空白
                    val longBreak = prev != null &&
                        java.time.Duration.between(prev.lines.last().endAt, first.at).seconds > 120
                    Row(Modifier.fillMaxWidth().padding(top = if (longBreak) 14.dp else 6.dp, bottom = 2.dp)) {
                        // 时间前面的 ▶：点一下从这段开始播
                        Text((if (first.rec.hasAudio) "▶ " else "") + first.at.format(T),
                            Modifier.width(76.dp).padding(top = 3.dp).clickable(enabled = first.rec.hasAudio) {
                                if (player.current?.name != first.rec.name) player.open(first.rec)
                                player.seekTo((first.seg.start * 1000).toInt())
                            },
                            color = if (first.rec.hasAudio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            fontSize = 12.sp)
                        Column(Modifier.weight(1f)) {
                            // 点名字可以直接改名、或者说明"其实是别人"
                            if (showWho) Text(p.who!! + " ›", Modifier.clickable {
                                    editing = Speakers.resolve(this@MainActivity, first.seg.speaker) },
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Medium)
                            // 段落里的每一句拼成一段文字，正在播放的那句高亮；点哪句从哪句播
                            val text = androidx.compose.ui.text.buildAnnotatedString {
                                p.lines.forEachIndexed { idx, l ->
                                    val active = l.rec.name == playingName && pos >= l.seg.start && pos < l.seg.end + 0.3
                                    pushStringAnnotation("line", idx.toString())
                                    if (active) pushStyle(androidx.compose.ui.text.SpanStyle(
                                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold))
                                    append(l.seg.text.trim())
                                    if (active) pop()
                                    pop()
                                }
                            }
                            @Suppress("DEPRECATION")
                            androidx.compose.foundation.text.ClickableText(
                                text, style = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onSurface, lineHeight = 26.sp),
                            ) { off ->
                                val idx = text.getStringAnnotations("line", off, off).firstOrNull()?.item?.toInt() ?: 0
                                val l = p.lines[idx]
                                if (l.rec.hasAudio) {
                                    if (player.current?.name != l.rec.name) player.open(l.rec)
                                    player.seekTo((l.seg.start * 1000).toInt())
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    @Composable
    private fun StatusCard(s: Status) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(s.link.label, style = MaterialTheme.typography.titleMedium)
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(6.dp))
                val batt = s.batteryMv?.let { "${batteryPct(it)}%%（%.2fV，按电压估算）".format(it / 1000.0) } ?: "—"
                Text("电池 $batt  ·  " + "%.1f KB/s".format(s.bytesPerSec / 1000.0),
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

    /** 每个人（合并后）说了几句，以及他说得最长的一句（手机上还有录音的），用来"听一下" */
    private class PersonStats(val count: Int, val clip: Pair<Recording, Segment>?, val sample: String?)

    private fun personStats(recs: List<Recording>): Map<String, PersonStats> {
        val count = HashMap<String, Int>()
        val clip = HashMap<String, Pair<Recording, Segment>>()
        val sample = HashMap<String, String>()
        recs.forEach { r -> Transcripts.get(this, r.name)?.segments?.forEach { seg ->
            val who = Speakers.resolve(this, seg.speaker)?.id ?: return@forEach
            if (seg.text.isBlank()) return@forEach
            count[who] = (count[who] ?: 0) + 1
            if ((sample[who]?.length ?: 0) < seg.text.length) sample[who] = seg.text
            if (r.hasAudio) {
                val old = clip[who]?.second
                if (old == null || seg.end - seg.start > old.end - old.start) clip[who] = r to seg
            }
        } }
        return (count.keys).associateWith { PersonStats(count[it] ?: 0, clip[it], sample[it]) }
    }

    private fun playClip(c: Pair<Recording, Segment>) =
        player.playRange(c.first, (c.second.start * 1000).toInt(), (c.second.end * 1000).toInt() + 300)

    /** 人物页：说过话的人，可以听一下、起名字、合并 */
    @Composable
    private fun PeoplePage(recs: List<Recording>) {
        @Suppress("UNUSED_EXPRESSION") speakerVersion
        val all = Speakers.all(this)
        val scope = rememberCoroutineScope()
        val stats = personStats(recs)
        // 没说过一句话的不显示（分离模型偶尔会多分出"空人"）；起过名字的一直显示
        val people = all.values.filter { it.mergedInto == null && ((stats[it.id]?.count ?: 0) > 0 || it.name != null) }
            .sortedWith(compareBy<Speaker>({ it.name == null }, { -(stats[it.id]?.count ?: 0) }))

        LazyColumn(Modifier.fillMaxWidth()) {
            if (people.isEmpty()) item {
                Text("还没有认出任何人。转写结果里出现的人会列在这里，你可以给他们起名字。",
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.outline)
            } else item {
                Text("先点「听一下」确认是谁。同一个人被分成了好几个，就点「和别人是同一个人」并到一起。",
                    Modifier.padding(top = 8.dp, bottom = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
            items(people, key = { it.id }) { p ->
                val st = stats[p.id]
                val aliases = all.values.filter { it.mergedInto != null && Speakers.resolve(this@MainActivity, it.id)?.id == p.id }
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name ?: p.defaultName, Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (p.name == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                        Text("${st?.count ?: 0} 句", color = MaterialTheme.colorScheme.outline, fontSize = 13.sp)
                    }
                    (st?.sample ?: p.sample)?.let { Text("「$it」", style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (aliases.isNotEmpty()) {
                        Text("已合并：" + aliases.joinToString("、") { it.name ?: it.defaultName },
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Row {
                        st?.clip?.let { c -> TextButton(onClick = { playClip(c) }) { Text("▶ 听一下") } }
                        TextButton(onClick = { editing = p }) { Text("改名字") }
                        if (people.size > 1) TextButton(onClick = { merging = p }) { Text("和别人是同一个人") }
                    }
                    if (aliases.isNotEmpty()) Row {
                        aliases.forEach { a ->
                            TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                Speakers.edit(this@MainActivity, a.id, unmerge = true); speakerVersion++ } }) {
                                Text("拆开 ${a.name ?: a.defaultName}")
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    /** 改名字 / 合并 的对话框，文字页和人物页共用 */
    @Composable
    private fun PersonDialogs(recs: List<Recording>) {
        val scope = rememberCoroutineScope()
        editing?.let { p ->
            var text by remember(p.id) { mutableStateOf(p.name ?: "") }
            val clip = personStats(recs)[p.id]?.clip
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("给 ${p.name ?: p.defaultName} 起名字") },
                text = {
                    Column {
                        OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("比如：我、老王、妈妈") })
                        Row {
                            clip?.let { c -> TextButton(onClick = { playClip(c) }) { Text("▶ 听一下") } }
                            TextButton(onClick = { merging = p; editing = null }) { Text("其实是别人…") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { Speakers.edit(this@MainActivity, p.id, name = text); speakerVersion++ }
                    editing = null }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
            )
        }
        merging?.let { p ->
            val stats = personStats(recs)
            val others = Speakers.all(this).values.filter {
                it.mergedInto == null && it.id != p.id && ((stats[it.id]?.count ?: 0) > 0 || it.name != null)
            }.sortedWith(compareBy<Speaker>({ it.name == null }, { -(stats[it.id]?.count ?: 0) }))
            AlertDialog(
                onDismissRequest = { merging = null },
                title = { Text("${p.name ?: p.defaultName} 其实是谁？") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        item {
                            Text("点名字合并。合并后以后两种声音都认成同一个人，之后还能拆开。",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        items(others, key = { it.id }) { t ->
                            val st = stats[t.id]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable {
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        Speakers.edit(this@MainActivity, p.id, mergeInto = t.id); speakerVersion++ }
                                    merging = null
                                }.padding(vertical = 8.dp)) {
                                    Text(t.name ?: t.defaultName, style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    st?.sample?.let { Text("「$it」", fontSize = 12.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline) }
                                }
                                st?.clip?.let { c -> TextButton(onClick = { playClip(c) }) { Text("▶") } }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { merging = null }) { Text("取消") } },
            )
        }
    }

    private fun statusLabel(r: Recording, tr: Transcript?): String = when {
        tr != null -> "已转写"
        !r.hasAudio -> "录音已过期"
        uploader.isUploaded(r.file) -> "等待转写"
        else -> "待上传"
    }

    companion object {
        private const val PARA_GAP_S = 3.0
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

        /** 锂电池电压 → 大致电量。小电池放电曲线，误差 ±10% 左右；插着 USB 充电时会偏高 */
        private val BATT_CURVE = listOf(4200 to 100, 4100 to 90, 4000 to 78, 3900 to 65, 3800 to 50,
            3750 to 40, 3700 to 30, 3650 to 20, 3600 to 12, 3500 to 5, 3300 to 0)

        fun batteryPct(mv: Int): Int {
            if (mv >= BATT_CURVE.first().first) return 100
            if (mv <= BATT_CURVE.last().first) return 0
            for (i in 0 until BATT_CURVE.size - 1) {
                val (v1, p1) = BATT_CURVE[i]; val (v2, p2) = BATT_CURVE[i + 1]
                if (mv in v2..v1) return p2 + (p1 - p2) * (mv - v2) / (v1 - v2)
            }
            return 0
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
