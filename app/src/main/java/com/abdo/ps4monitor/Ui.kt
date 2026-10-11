@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import androidx.compose.ui.graphics.compositeOver
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Inbox { val url = mutableStateOf<String?>(null) }    // non-null => the "Download to PS4" dialog is open

/** Navigation route of the PKG inspector (the path is URL-encoded because it contains slashes). */
fun pkgRoute(ps4Id: String, path: String) = "pkg/${Uri.encode(ps4Id)}/${Uri.encode(path)}"

// ---------------------------------------------------------------- small building blocks
@Composable fun Ico(@DrawableRes id: Int, size: Dp = 24.dp, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) =
    Icon(painterResource(id), null, modifier.size(size), tint)

/** Single-line label that is ellipsised instead of breaking into a second line (buttons, chips, tabs). */
@Composable fun Lbl(t: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified,
                    weight: FontWeight? = null, textAlign: TextAlign? = null) =
    Text(t, modifier, color = color, fontWeight = weight, textAlign = textAlign, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style)

@Composable fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }

@Composable fun Dim(t: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(t, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable fun EmptyState(@DrawableRes icon: Int, text: String) =
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Ico(icon, 48.dp, MaterialTheme.colorScheme.outline); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }

@Composable fun Chips(opts: List<Int>, sel: Int, unit: String, on: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { opts.forEach { FilterChip(selected = it == sel, onClick = { on(it) }, label = { Lbl("$it $unit") }) } }
}
@Composable fun ToggleRow(label: String, key: String, def: Boolean) {
    var v by remember { mutableStateOf(Store.sp.getBoolean(key, def)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f)); Switch(checked = v, onCheckedChange = { v = it; Store.sp.edit().putBoolean(key, it).apply() })
    }
}
@Composable fun BackHeader(title: String, nav: androidx.navigation.NavController, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.popBackStack() }) { Ico(R.drawable.ic_arrow_back) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

// ---------------------------------------------------------------- download state look
fun stateIcon(s: DlState) = when (s) {
    DlState.COMPLETED -> R.drawable.ic_check_circle
    DlState.FAILED, DlState.SERVER_DOWN -> R.drawable.ic_error
    DlState.NOT_STARTED, DlState.STALLED -> R.drawable.ic_warning
    DlState.PAUSED -> R.drawable.ic_pause
    DlState.VERIFYING -> R.drawable.ic_search
    DlState.DOWNLOADING -> R.drawable.ic_download
    else -> R.drawable.ic_schedule
}
@Composable fun stateColors(s: DlState): Pair<Color, Color> { val c = MaterialTheme.colorScheme; return when (s) {
    DlState.COMPLETED -> c.tertiaryContainer to c.onTertiaryContainer
    DlState.FAILED, DlState.NOT_STARTED -> c.errorContainer to c.onErrorContainer
    DlState.STALLED, DlState.SERVER_DOWN, DlState.PAUSED -> c.secondaryContainer to c.onSecondaryContainer
    else -> c.primaryContainer to c.onPrimaryContainer } }

fun resultText(r: SubmitResult) = Tx.t(when (r) {
    is SubmitResult.Accepted -> tr("Request accepted. Waiting for ezRemote Server to start the download…", "قُبل الطلب. بانتظار أن يبدأ خادم ezRemote التحميل…")
    is SubmitResult.Rejected -> r.message
    is SubmitResult.Unreachable -> r.message
    is SubmitResult.Duplicate -> r.message
    is SubmitResult.Invalid -> r.message
})

/** Real state only: no percentage or ETA unless the total size is actually known. */
@Composable fun DownloadCard(d: Download, onOpen: () -> Unit, onLong: (() -> Unit)? = null, selected: Boolean? = null,
                             onPause: (() -> Unit)? = null, onResume: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val ps4 = Ps4Repo.get(d.ps4Id)
    val (bg, fg) = stateColors(d.state)
    val shape = MaterialTheme.shapes.large
    // tint taken from the game's cover
    val accent by produceState<Color?>(null, d.id, d.iconReady) { value = CoverColor.load(d)?.let { Color(it) } }
    val acc = accent
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val container = when {
        selected == true -> MaterialTheme.colorScheme.secondaryContainer
        acc != null -> acc.copy(alpha = 0.16f).compositeOver(base)
        else -> base
    }
    val tint = acc ?: MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onOpen, onLongClick = onLong), shape = shape, colors = CardDefaults.cardColors(containerColor = container)) {
        Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val art by produceState<Bitmap?>(null, d.id, d.iconReady, d.state == DlState.COMPLETED) { value = PkgThumbs.forDownload(d, 160) }
            val pic = art
            Box(Modifier.size(56.dp)) {
                if (pic != null) Image(pic.asImageBitmap(), null, Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
                    Ico(if (selected == true) R.drawable.ic_check else stateIcon(d.state), 26.dp, fg) }
                if (pic != null) Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(bg).border(2.dp, container, CircleShape), contentAlignment = Alignment.Center) {
                    Ico(if (selected == true) R.drawable.ic_check else stateIcon(d.state), 13.dp, fg) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.pkgTitle ?: d.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Dim(listOfNotNull(ps4?.name ?: tr("Removed PS4", "جهاز محذوف"), d.titleId).joinToString("  •  "), maxLines = 1)
                val exp = d.expectedSize?.takeIf { it > 0 }
                if (d.currentSize > 0 || d.state == DlState.COMPLETED)
                    Text(if (exp != null) "${Fmt.bytes(d.currentSize)} / ${Fmt.bytes(exp)}" + (d.pct?.let { "  •  $it%" } ?: "") else Fmt.bytes(d.currentSize),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val f = d.frac
                val bar = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                if (f != null && (d.state.active || d.state == DlState.PAUSED)) LinearProgressIndicator(progress = { f }, bar, color = tint, trackColor = tint.copy(alpha = 0.22f))
                else if (d.state == DlState.DOWNLOADING) LinearProgressIndicator(bar, color = tint, trackColor = tint.copy(alpha = 0.22f))            // total unknown: indeterminate, no fake %
                if (d.state == DlState.DOWNLOADING) Dim(Fmt.mbs(d.speed) + if (d.etaSec >= 0) "  •  ETA ${Fmt.dur(d.etaSec)}" else "", maxLines = 1)
                Text(d.state.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val extra = d.errorMessage ?: d.note
                if (extra.isNotBlank()) Dim(Tx.t(extra), maxLines = 3)
                if (d.superseded) Dim(tr("Replaced by a newer attempt", "استُبدل بمحاولة أحدث"))
                if (selected == null && !d.superseded && d.sourceUrl.isNotBlank() && (d.state == DlState.NOT_STARTED || d.state == DlState.FAILED))
                    TextButton(onClick = { scope.launch { Toast.makeText(ctx, resultText(DownloadMonitor.retry(d.id)), Toast.LENGTH_LONG).show() } }) { Lbl(tr("Retry", "إعادة المحاولة")) }
            }
            if (selected == null) {
                if (d.state == DlState.PAUSED && onResume != null) IconButton(onClick = onResume) { Ico(R.drawable.ic_play, 24.dp, MaterialTheme.colorScheme.primary) }
                else if (d.state.active && d.state != DlState.SUBMITTING && onPause != null) IconButton(onClick = onPause) { Ico(R.drawable.ic_pause, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
                else if (!d.state.active && d.state != DlState.PAUSED && onDelete != null) IconButton(onClick = onDelete) { Ico(R.drawable.ic_delete, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs shared by Home / Downloads
@Composable fun ConfirmPause(id: String, close: () -> Unit) {
    val p = Ps4Repo.get(DownloadRepo.get(id)?.ps4Id)
    val v2 = p != null && EzServer.isV2Known(p)
    AlertDialog(onDismissRequest = close, title = { Text(tr("Pause this download?", "إيقاف هذا التحميل مؤقتًا؟")) },
        text = { if (v2) Text(tr("Only this download is paused. Your other downloads keep running and the partial file is kept.", "يُوقف هذا التحميل فقط. تستمر تحميلاتك الأخرى ويبقى الملف الجزئي.")) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("ezRemote Server only reads bg_download_history.json when it starts, so to pause it has to be stopped. That stops ALL background downloads and installs on ${p?.name ?: "the PS4"}.",
                "خادم ezRemote لا يقرأ bg_download_history.json إلا عند إقلاعه، لذلك يجب إيقافه ليتوقف التحميل. هذا يوقف كل التحميلات والتثبيتات في الخلفية على ${p?.name ?: "الـPS4"}."))
            Dim(tr("This download is then marked as stopped (failed_attempts = 5) and stays paused until you resume it. Your other downloads continue when you launch ezRemote on the PS4 again; the partial file is kept.",
                "ثم يُعلَّم هذا التحميل كمتوقف (failed_attempts = 5) ويبقى متوقفًا حتى تستأنفه. تستمر تحميلاتك الأخرى عند تشغيل ezRemote مجددًا على الـPS4؛ والملف الجزئي يبقى."))
        } },
        confirmButton = { TextButton(onClick = { Ops.launch(tr("Pausing…", "إيقاف مؤقت…")) { DownloadMonitor.pause(id) }; close() }) { Lbl(tr("Pause", "إيقاف مؤقت")) } },
        dismissButton = { TextButton(onClick = close) { Lbl(tr("Cancel", "إلغاء")) } })
}

@Composable fun ConfirmResume(id: String, close: () -> Unit) {
    val v2 = Ps4Repo.get(DownloadRepo.get(id)?.ps4Id)?.let { EzServer.isV2Known(it) } == true
    AlertDialog(onDismissRequest = close, title = { Text(tr("Resume this download?", "استئناف هذا التحميل؟")) },
        text = { if (v2) Text(tr("The download continues from the partial file. Nothing needs to be restarted on the PS4.", "يكمل التحميل من الملف الجزئي. لا حاجة لإعادة تشغيل أي شيء على الـPS4.")) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("failed_attempts is set back to 1 in bg_download_history.json (ezRemote Server is stopped first if it is running).", "يُعاد failed_attempts إلى 1 في bg_download_history.json (يُوقف خادم ezRemote أولًا إن كان يعمل)."))
            Dim(tr("Then launch ezRemote on the PS4 so the server reloads the list. The download continues from the partial file and this app picks it up automatically.", "ثم شغّل ezRemote على الـPS4 ليعيد الخادم تحميل القائمة. يكمل التحميل من الملف الجزئي ويلتقطه التطبيق تلقائيًا."))
        } },
        confirmButton = { TextButton(onClick = { Ops.launch(tr("Preparing resume…", "تجهيز الاستئناف…")) { DownloadMonitor.resume(id) }; close() }) { Lbl(tr("Resume", "استئناف")) } },
        dismissButton = { TextButton(onClick = close) { Lbl(tr("Cancel", "إلغاء")) } })
}

@Composable fun ConfirmDeleteDownloads(ids: List<String>, onDone: () -> Unit, close: () -> Unit) {
    val items = ids.mapNotNull { DownloadRepo.get(it) }
    val v2 = items.firstOrNull()?.let { Ps4Repo.get(it.ps4Id) }?.let { EzServer.isV2Known(it) } == true
    var files by remember { mutableStateOf(items.any { it.state != DlState.COMPLETED }) }
    AlertDialog(onDismissRequest = close, title = { Text(tr("Delete ${ids.size} download(s)?", "حذف ${ids.size} تحميل؟")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (files) tr("The download is cancelled (removed from ezRemote Server's list) and its file is deleted from the PS4. This cannot be undone.", "يُلغى التحميل (يُزال من قائمة خادم ezRemote) ويُحذف ملفه من الـPS4. لا يمكن التراجع.")
                 else tr("Removed from this list only. Files on the PS4 are kept.", "سيُحذف من هذه القائمة فقط. تبقى الملفات على الـPS4."))
            Row(Modifier.fillMaxWidth().clickable { files = !files }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(files, { files = it }); Text(tr("Also delete the file(s) from the PS4", "احذف الملف (الملفات) من الـPS4 أيضًا"), Modifier.weight(1f))
            }
            if (!v2 && items.any { it.state.active }) Dim(tr("A running download is cancelled by stopping ezRemote Server (it has no per-download cancel) and removing its entry from bg_download_history.json. Your other downloads continue when you launch ezRemote again.",
                "يُلغى التحميل الجاري بإيقاف خادم ezRemote (لا يملك إلغاءً لتحميل واحد) وإزالة إدخاله من bg_download_history.json. تستمر تحميلاتك الأخرى عند تشغيل ezRemote مجددًا."))
        } },
        confirmButton = { TextButton(onClick = {
            Ops.launch(tr("Deleting…", "جارٍ الحذف…")) { ids.map { DownloadMonitor.delete(it, files) }.joinToString("\n") }; onDone(); close()
        }) { Lbl(tr("Delete", "حذف"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = close) { Lbl(tr("Cancel", "إلغاء")) } })
}

// ---------------------------------------------------------------- clipboard + send dialog
@Composable fun ClipCard() {
    val clip = LocalClipboardManager.current
    var link by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        delay(500)
        val m = Regex("""https?://\S+""").find(clip.getText()?.text.orEmpty())?.value
        if (m != null && m != Store.sp.getString("lastclip", "")) link = m
    }
    val l = link
    if (l != null) Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ico(R.drawable.ic_link, 20.dp); Text(tr("Link in clipboard", "رابط في الحافظة"), fontWeight = FontWeight.SemiBold)
            }
            Text(l, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Store.sp.edit().putString("lastclip", l).apply(); Inbox.url.value = l; link = null }) { Lbl(tr("Download to PS4", "تحميل إلى PS4")) }
                TextButton(onClick = { Store.sp.edit().putString("lastclip", l).apply(); link = null }) { Lbl(tr("Dismiss", "تجاهل")) }
            }
        }
    }
}

/** Choose PS4 -> confirm destination and file name -> send. */
@Composable fun SendDialog(initialUrl: String = "", onAddPs4: () -> Unit, onSent: () -> Unit, close: () -> Unit) {
    val ps4s by Ps4Repo.list.collectAsState()
    var url by remember { mutableStateOf(initialUrl) }
    var sel by remember { mutableStateOf(Ps4Repo.activeId.value ?: ps4s.firstOrNull()?.id) }
    val p = ps4s.firstOrNull { it.id == sel }
    var dest by remember(sel) { mutableStateOf(p?.dest ?: "/data/pkg") }
    var name by remember { mutableStateOf(initialUrl.takeIf { it.startsWith("http") }?.let { Names.fromUrl(it) }.orEmpty()) }
    var nameEdited by remember { mutableStateOf(false) }
    val links = Regex("""https?://\S+""").findAll(url).map { it.value }.toList().distinct().take(10)
    LaunchedEffect(links.firstOrNull()) { if (!nameEdited) name = links.firstOrNull()?.let { Names.fromUrl(it) }.orEmpty() }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(tr("Download to PS4", "تحميل إلى PS4")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ps4s.isEmpty()) {
                Text(tr("Add a PS4 first.", "أضف جهاز PS4 أولًا."))
                TextButton(onClick = onAddPs4) { Lbl(tr("Add PS4", "إضافة PS4")) }
            } else {
                if (ps4s.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ps4s.forEach { FilterChip(sel == it.id, { sel = it.id }, label = { Lbl(it.name) }) } }
                else Text("PS4: ${ps4s[0].name}", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(url, { url = it }, label = { Text(tr("Link(s), one per line", "الرابط (رابط في كل سطر)")) }, minLines = 2, maxLines = 6)
                OutlinedTextField(dest, { dest = it }, label = { Text(tr("Destination folder on the PS4", "مجلد الوجهة على الـPS4")) }, singleLine = true)
                if (links.size <= 1) OutlinedTextField(name, { name = it; nameEdited = true }, label = { Text(tr("File name on the PS4", "اسم الملف على الـPS4")) }, singleLine = true,
                    supportingText = { Text(tr("Must end with .pkg to appear in the PS4 package list", "يجب أن ينتهي بـ .pkg ليظهر في قائمة الحزم")) })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (msg.isNotEmpty()) Text(msg, fontWeight = FontWeight.SemiBold)
                Dim(tr("ezRemote Server saves to exactly this folder and file name, then renames from .tmp when finished. The size is read from ezRemote Server.", "يحفظ خادم ezRemote في هذا المجلد وبهذا الاسم تمامًا، ثم يعيد التسمية من .tmp عند الانتهاء. الحجم يُقرأ من خادم ezRemote."))
            }
        } },
        confirmButton = { TextButton(enabled = !busy && p != null && links.isNotEmpty(), onClick = {
            val target = p ?: return@TextButton
            busy = true; msg = ""
            scope.launch {
                val out = StringBuilder(); var ok = 0
                links.forEachIndexed { i, l ->
                    if (i > 0) delay(4000)
                    val r = DownloadMonitor.submit(target, l, dest, if (links.size == 1) name.trim().ifBlank { null } else null)
                    if (r is SubmitResult.Accepted) ok++
                    out.append(if (links.size > 1) "${i + 1}/${links.size}: " else "").append(resultText(r)).append('\n'); msg = out.toString()
                }
                busy = false
                if (ok == links.size && ok > 0) { Toast.makeText(ctx, tr("Request accepted. Waiting for ezRemote Server…", "قُبل الطلب. بانتظار خادم ezRemote…"), Toast.LENGTH_LONG).show(); close(); onSent() }
            }
        }) { Lbl(tr("Send", "إرسال")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Lbl(tr("Close", "إغلاق")) } })
}
