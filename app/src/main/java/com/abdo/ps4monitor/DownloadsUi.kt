@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import androidx.compose.ui.graphics.Brush
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@Composable fun DownloadsScreen(nav: NavController) {
    val all by DownloadRepo.all.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    var pauseId by remember { mutableStateOf<String?>(null) }
    var resumeId by remember { mutableStateOf<String?>(null) }
    val active = all.filter { it.state.active }
    val paused = all.filter { it.state == DlState.PAUSED }
    val done = all.filter { it.state == DlState.COMPLETED }
    val failed = all.filter { it.state == DlState.FAILED || it.state == DlState.NOT_STARTED }
    val shown = when (tab) { 0 -> active; 1 -> paused; 2 -> done; else -> failed }.sortedByDescending { it.createdAt }
    val selIds = sel.filter { id -> shown.any { it.id == id } }
    val selecting = selIds.isNotEmpty()
    BackHandler(enabled = selecting) { sel = emptySet() }
    LaunchedEffect(Unit) { Ps4Repo.active()?.let { DownloadMonitor.probe(it) } }       // discovers downloads started elsewhere
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selecting) {
                IconButton(onClick = { sel = emptySet() }) { Ico(R.drawable.ic_close) }
                Text("${selIds.size} " + tr("selected", "محدد"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { sel = shown.map { it.id }.toSet() }) { Ico(R.drawable.ic_select_all) }
                IconButton(onClick = { confirmDelete = selIds }) { Ico(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error) }
            } else {
                Text(tr("Downloads", "التحميلات"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                if (shown.isNotEmpty()) IconButton(onClick = { sel = shown.map { it.id }.toSet() }) { Ico(R.drawable.ic_select_all) }
                FilledTonalButton(onClick = { Inbox.url.value = "" }, contentPadding = PaddingValues(horizontal = 14.dp)) { Ico(R.drawable.ic_add, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Link", "رابط")) }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, divider = {}) {
            listOf(tr("Active", "نشطة") to active.size, tr("Paused", "متوقفة") to paused.size, tr("Completed", "مكتملة") to done.size, tr("Failed", "فاشلة") to failed.size).forEachIndexed { i, (t, n) ->
                Tab(tab == i, { tab = i; sel = emptySet() }, text = { Lbl("$t $n") })
            }
        }
        if (shown.isEmpty()) EmptyState(R.drawable.ic_download, tr("Nothing here yet.", "لا يوجد شيء هنا بعد."))
        LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.id }) { d ->
                DownloadCard(d,
                    onOpen = { if (selecting) sel = if (d.id in sel) sel - d.id else sel + d.id else nav.navigate("downloads/${d.id}") },
                    onLong = { sel = sel + d.id }, selected = if (selecting) d.id in selIds else null,
                    onPause = { pauseId = d.id }, onResume = { resumeId = d.id }, onDelete = { confirmDelete = listOf(d.id) })
            }
        }
    }
    pauseId?.let { ConfirmPause(it) { pauseId = null } }
    resumeId?.let { ConfirmResume(it) { resumeId = null } }
    confirmDelete?.let { ConfirmDeleteDownloads(it, onDone = { sel = emptySet() }, close = { confirmDelete = null }) }
}

@Composable fun DownloadDetail(id: String, nav: NavController) {
    val all by DownloadRepo.all.collectAsState()
    val d = all.firstOrNull { it.id == id }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var pause by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var install by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackHeader(tr("Download", "التحميل"), nav)
        if (d == null) { Text(tr("This download was removed.", "تم حذف هذا التحميل.")); return@Column }
        val ps4 = Ps4Repo.get(d.ps4Id)
        val (bg, fg) = stateColors(d.state)
        val accent by produceState<Color?>(null, d.id, d.iconReady) { value = CoverColor.load(d)?.let { Color(it) } }
        val acc = accent
        val tint = acc ?: MaterialTheme.colorScheme.primary
        val headMod = if (acc != null) Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(acc.copy(alpha = 0.32f), Color.Transparent))).padding(12.dp) else Modifier.fillMaxWidth()
        Row(headMod, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val art by produceState<Bitmap?>(null, d.id, d.iconReady) { value = PkgThumbs.forDownload(d, 256) }
            val pic = art
            if (pic != null) Image(pic.asImageBitmap(), null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
            else Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(bg), contentAlignment = Alignment.Center) { Ico(stateIcon(d.state), 28.dp, fg) }
            Column(Modifier.weight(1f)) {
                Text(d.pkgTitle ?: d.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Dim("${listOfNotNull(d.titleId).joinToString()}  ${ps4?.name ?: tr("Removed PS4", "جهاز محذوف")}  •  " + tr("attempt", "المحاولة") + " ${d.attempt}", maxLines = 1)
            }
        }
        Panel {
            Text(d.state.label, fontWeight = FontWeight.SemiBold, color = tint)
            (d.errorMessage ?: d.note).takeIf { it.isNotBlank() }?.let { Text(Tx.t(it)) }
        }
        Panel {
            val exp = d.expectedSize?.takeIf { it > 0 }
            Text(tr("Downloaded: ", "المحمَّل: ") + Fmt.bytes(d.currentSize))
            if (exp != null) {
                Text(tr("Total: ", "الإجمالي: ") + Fmt.bytes(exp))
                d.frac?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = tint, trackColor = tint.copy(alpha = 0.22f)) }
                Text(tr("Progress: ", "التقدم: ") + "${d.pct}%")
            } else Text(tr("Total: unknown — no percentage or ETA is shown", "الإجمالي: غير معروف — لا تُعرض نسبة ولا وقت متبقٍّ"))
            if (d.state == DlState.DOWNLOADING) Text(tr("Speed: ", "السرعة: ") + "${Fmt.mbs(d.speed)}  •  ${Fmt.mbit(d.speed)}")
            Text("ETA: " + if (d.etaSec >= 0) Fmt.dur(d.etaSec) else "--")
        }
        Panel {
            d.path?.let { Dim(tr("File: ", "الملف: ") + it) }
            if (d.sourceUrl.isNotBlank()) Dim(tr("Link: ", "الرابط: ") + d.sourceUrl.take(120))
            Dim(tr("Sent: ", "أُرسل: ") + Fmt.dt(d.submittedAt) + "   " + tr("Finished: ", "انتهى: ") + Fmt.dt(d.completedAt))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.state.active && d.state != DlState.SUBMITTING) FilledTonalButton(onClick = { pause = true }) { Ico(R.drawable.ic_pause, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Pause", "إيقاف مؤقت")) }
            if (d.state == DlState.PAUSED) Button(onClick = { resume = true }) { Ico(R.drawable.ic_play, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Resume", "استئناف")) }
            if (!d.superseded && d.sourceUrl.isNotBlank() && (d.state == DlState.FAILED || d.state == DlState.NOT_STARTED))
                Button(onClick = { scope.launch { Toast.makeText(ctx, resultText(DownloadMonitor.retry(d.id)), Toast.LENGTH_LONG).show() } }) { Ico(R.drawable.ic_refresh, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Retry", "إعادة المحاولة")) }
            if (d.state == DlState.COMPLETED && ps4 != null && d.path?.lowercase()?.endsWith(".pkg") == true) {
                Button(onClick = { install = true }) { Ico(R.drawable.ic_install, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Install", "تثبيت")) }
                FilledTonalButton(onClick = { nav.navigate(pkgRoute(ps4.id, d.path!!)) }) { Ico(R.drawable.ic_package, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Inside the PKG", "ما بداخل الـPKG")) }
            }
            OutlinedButton(onClick = { delete = true }) { Ico(R.drawable.ic_delete, 18.dp, MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp)); Lbl(tr("Delete", "حذف"), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (pause) ConfirmPause(id) { pause = false }
    if (resume) ConfirmResume(id) { resume = false }
    if (delete) ConfirmDeleteDownloads(listOf(id), onDone = { nav.popBackStack() }, close = { delete = false })
    if (install) { val p = Ps4Repo.get(d?.ps4Id); val f = d?.path; if (p != null && f != null) ConfirmInstall(p, listOf(f), close = { install = false }) }
}
