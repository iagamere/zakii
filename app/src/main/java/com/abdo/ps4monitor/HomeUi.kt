@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.coroutines.launch

private fun go(nav: NavController, r: String) = nav.navigate(r) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }

@Composable private fun ActionTile(@DrawableRes icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) =
    Card(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Ico(icon, 26.dp, MaterialTheme.colorScheme.onSecondaryContainer)
            Lbl(label, Modifier.fillMaxWidth(), MaterialTheme.typography.labelMedium, MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center)
        }
    }

@Composable fun HomeScreen(nav: NavController) {
    val ps4s by Ps4Repo.list.collectAsState()
    val activeId by Ps4Repo.activeId.collectAsState()
    val statuses by DownloadMonitor.status.collectAsState()
    val dls by DownloadRepo.all.collectAsState()
    val ps4 = ps4s.firstOrNull { it.id == activeId }
    val st = ps4?.let { statuses[it.id] } ?: Ps4Status()
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var probeMsg by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf(false) }
    var pauseId by remember { mutableStateOf<String?>(null) }
    var resumeId by remember { mutableStateOf<String?>(null) }
    fun refresh() { val p = ps4 ?: return; probing = true; scope.launch { probeMsg = DownloadMonitor.probe(p); probing = false } }
    LaunchedEffect(ps4?.id) { if (ps4 != null) refresh() }

    val mine = dls.filter { it.ps4Id == ps4?.id }
    val active = mine.filter { it.state.active }
    val paused = mine.filter { it.state == DlState.PAUSED }
    val recent = mine.filter { !it.state.active && it.state != DlState.PAUSED }.sortedByDescending { it.updatedAt }.take(4)
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { ClipCard() }
        item {
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ps4 == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Ico(R.drawable.ic_console, 32.dp); Text(tr("No PS4 yet", "لا يوجد PS4 بعد"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Text(tr("Add your PS4 (IP address) to send and monitor downloads.", "أضف جهاز PS4 (عنوان IP) لإرسال التحميلات ومراقبتها."))
                        Button(onClick = { nav.navigate("settings/ps4/new") }) { Ico(R.drawable.ic_add, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Add PS4", "إضافة PS4")) }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Ico(R.drawable.ic_console, 32.dp)
                            Column(Modifier.weight(1f)) {
                                Text(ps4.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${ps4.host}  •  :${ps4.httpPort}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (ps4s.size > 1) Box {
                                TextButton(onClick = { pick = true }) { Lbl(tr("Switch", "تبديل")) }
                                DropdownMenu(pick, { pick = false }) { ps4s.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { Ps4Repo.setActive(p.id); pick = false }) } }
                            }
                        }
                        Text(tr("ezRemote web", "ويب ezRemote") + ": ${st.web.label}   •   " + tr("ezRemote Server", "خادم ezRemote") + ": ${st.bg.label}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        if (probing) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else if (probeMsg.isNotBlank()) Text(Tx.lines(probeMsg), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(enabled = !probing, onClick = { refresh() }) { Ico(R.drawable.ic_refresh, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Refresh", "تحديث")) }
                            FilledTonalButton(onClick = { nav.navigate("settings/ps4/${ps4.id}") }) { Lbl(tr("Edit", "تعديل")) }
                            FilledTonalButton(onClick = { nav.navigate("settings/ps4/new") }) { Ico(R.drawable.ic_add, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Add PS4", "إضافة PS4")) }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(R.drawable.ic_link, tr("Link", "رابط"), Modifier.weight(1f)) { Inbox.url.value = "" }
                ActionTile(R.drawable.ic_globe, tr("Browser", "المتصفح"), Modifier.weight(1f)) { go(nav, "browser") }
                ActionTile(R.drawable.ic_download, tr("Downloads", "التحميلات"), Modifier.weight(1f)) { go(nav, "downloads") }
                ActionTile(R.drawable.ic_folder, tr("Files", "الملفات"), Modifier.weight(1f)) { nav.navigate("files") }
            }
        }
        item { SectionTitle(tr("Active downloads", "التحميلات النشطة") + " (${active.size})") }
        if (active.isEmpty()) item { Dim(tr("Nothing is being downloaded.", "لا يوجد تحميل جارٍ.")) }
        items(active.take(3), key = { it.id }) { DownloadCard(it, { nav.navigate("downloads/${it.id}") }, onPause = { pauseId = it.id }) }
        if (active.size > 3) item { TextButton(onClick = { go(nav, "downloads") }) { Lbl(tr("View all", "عرض الكل") + " ${active.size}") } }
        if (paused.isNotEmpty()) item { SectionTitle(tr("Paused", "متوقفة") + " (${paused.size})") }
        items(paused.take(3), key = { "p" + it.id }) { DownloadCard(it, { nav.navigate("downloads/${it.id}") }, onResume = { resumeId = it.id }) }
        if (recent.isNotEmpty()) item { SectionTitle(tr("Recent downloads", "آخر التحميلات")) }
        items(recent, key = { "r" + it.id }) { DownloadCard(it, { nav.navigate("downloads/${it.id}") }) }
    }
    pauseId?.let { ConfirmPause(it) { pauseId = null } }
    resumeId?.let { ConfirmResume(it) { resumeId = null } }
}
