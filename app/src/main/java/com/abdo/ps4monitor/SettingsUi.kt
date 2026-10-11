@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable private fun NavRow(icon: Int, title: String, sub: String, onClick: () -> Unit) =
    Card(onClick = onClick, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Ico(icon, 24.dp, MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); if (sub.isNotEmpty()) Dim(sub, maxLines = 2) }
            Ico(R.drawable.ic_arrow_forward, 20.dp, MaterialTheme.colorScheme.outline)
        }
    }

@Composable fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val s0 = remember { Store.settings() }
    var iv by remember { mutableIntStateOf(s0.interval) }; var st by remember { mutableIntStateOf(s0.stuck) }
    val ignoring = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text(tr("Settings", "الإعدادات"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item { NavRow(R.drawable.ic_console, tr("PS4 consoles", "أجهزة PS4"), tr("Add, edit and choose the active PS4", "إضافة وتعديل واختيار الجهاز النشط")) { nav.navigate("settings/ps4s") } }
        item { NavRow(R.drawable.ic_download, tr("ezRemote Server", "خادم ezRemote"), tr("Status, send the server to the PS4, download settings, log", "الحالة، إرسال السيرفر إلى الـPS4، إعدادات التحميل، السجل")) { nav.navigate("settings/server") } }
        item { Panel {
            SectionTitle(tr("Language", "اللغة"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0 to tr("Phone", "الهاتف"), 1 to "English", 2 to "العربية").forEach { (i, t) -> FilterChip(Lang.mode == i, { Store.setLang(i) }, label = { Lbl(t) }) } }
            SectionTitle(tr("Appearance", "المظهر"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(tr("System", "النظام"), tr("Light", "فاتح"), tr("Dark", "داكن")).forEachIndexed { i, t -> FilterChip(Store.theme.intValue == i, { Store.setTheme(i) }, label = { Lbl(t) }) } }
            if (android.os.Build.VERSION.SDK_INT >= 31) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Text(tr("Use phone colours", "استخدام ألوان الهاتف")); Dim(tr("Follows your wallpaper / system accent colour", "يتبع لون الهاتف الأساسي")) }
                Switch(Store.dynamic.value, { Store.setDynamic(it) })
            }
        } }
        item { Panel {
            SectionTitle(tr("Monitoring", "المراقبة"))
            Text(tr("Polling interval", "فاصل الفحص")); Chips(listOf(2, 3, 5, 10), iv, tr("s", "ث")) { iv = it; Store.putInt("poll", it) }
            Text(tr("Stalled after no growth for", "يُعتبر متعثّرًا بعد عدم نمو لمدة")); Chips(listOf(30, 60, 120, 300), st, tr("s", "ث")) { st = it; Store.putInt("stuck", it) }
            HorizontalDivider()
            ToggleRow(tr("Download notifications", "إشعارات التحميل"), "notif", true)
            ToggleRow(tr("Alert me when a download is interrupted", "نبّهني عند انقطاع تحميل"), "alert_int", true)
        } }
        if (!ignoring) item { Panel {
            Text(tr("Battery optimization may pause background monitoring on some phones.", "قد يوقف توفير البطارية المراقبة في الخلفية على بعض الهواتف."))
            Button(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Lbl(tr("Battery settings", "إعدادات البطارية")) }
        } }
        item { NavRow(R.drawable.ic_search, tr("Advanced", "متقدم"), tr("Debug log, stop ezRemote Server", "سجل التصحيح، إيقاف خادم ezRemote")) { nav.navigate("settings/advanced") } }
        item { Dim("PS4 Download Monitor 4.2") }
    }
}

@Composable fun AdvancedScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var confirmStop by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackHeader(tr("Advanced", "متقدم"), nav)
        NavRow(R.drawable.ic_search, tr("Debug log", "سجل التصحيح"), "") { nav.navigate("settings/log") }
        Panel {
            OutlinedButton(onClick = { confirmStop = true }, enabled = Ps4Repo.active() != null) { Ico(R.drawable.ic_stop, 18.dp, MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp)); Lbl(tr("Stop ezRemote Server", "إيقاف خادم ezRemote"), color = MaterialTheme.colorScheme.error) }
            Dim(tr("Stops ALL its background downloads and installs; they resume from where they stopped once ezRemote is launched again on the PS4.", "يوقف كل تحميلاته وتثبيتاته في الخلفية؛ وتُستأنف من حيث توقفت عند تشغيل ezRemote مجددًا على الـPS4."))
        }
    }
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text(tr("Stop ezRemote Server?", "إيقاف خادم ezRemote؟")) },
        text = { Text(tr("All background downloads and installs on ${Ps4Repo.active()?.name ?: "the PS4"} will stop. Launch ezRemote on the PS4 to start it again.", "ستتوقف كل التحميلات والتثبيتات في الخلفية على ${Ps4Repo.active()?.name ?: "الـPS4"}. شغّل ezRemote على الـPS4 لتشغيله مجددًا.")) },
        confirmButton = { TextButton(onClick = {
            val p = Ps4Repo.active(); confirmStop = false
            if (p != null) scope.launch {
                val stopped = withContext(Dispatchers.IO) { EzServer.stop(p) }
                Toast.makeText(ctx, if (stopped) tr("ezRemote Server stopped.", "توقف خادم ezRemote.") else tr("It still answers; it was not stopped.", "ما زال يستجيب؛ لم يتوقف."), Toast.LENGTH_LONG).show()
            }
        }) { Lbl(tr("Stop", "إيقاف"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Lbl(tr("Cancel", "إلغاء")) } })
}

@Composable fun LogScreen(nav: NavController) {
    val log by DownloadMonitor.log.collectAsState()
    Column(Modifier.padding(16.dp)) {
        BackHeader(tr("Debug log", "سجل التصحيح"), nav) { TextButton(onClick = { DownloadMonitor.clearLog() }) { Lbl(tr("Clear", "مسح")) } }
        if (log.isEmpty()) Text("(empty)")
        LazyColumn { items(log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) } }     // raw technical text stays English on purpose
    }
}

@Composable fun Ps4ListScreen(nav: NavController) {
    val list by Ps4Repo.list.collectAsState(); val activeId by Ps4Repo.activeId.collectAsState()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader(tr("PS4 consoles", "أجهزة PS4"), nav)
        if (list.isEmpty()) EmptyState(R.drawable.ic_console, tr("No PS4 added yet.", "لم تُضف أي جهاز PS4 بعد."))
        list.forEach { p ->
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = p.id == activeId, onClick = { Ps4Repo.setActive(p.id) })
                    Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Dim("${p.host}  •  ${p.dest}", maxLines = 1) }
                    TextButton(onClick = { nav.navigate("settings/ps4/${p.id}") }) { Lbl(tr("Edit", "تعديل")) }
                }
            }
        }
        Button(onClick = { nav.navigate("settings/ps4/new") }) { Ico(R.drawable.ic_add, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Add PS4", "إضافة PS4")) }
    }
}

@Composable fun Ps4EditScreen(id: String, nav: NavController) {
    val existing = remember(id) { Ps4Repo.get(id) }
    val isNew = existing == null
    var name by remember { mutableStateOf(existing?.name ?: "PS4") }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var http by remember { mutableStateOf((existing?.httpPort ?: 8080).toString()) }
    var dest by remember { mutableStateOf(existing?.dest ?: "/data/pkg") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun build() = Ps4(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim().ifBlank { "PS4" }, host = host.trim(), httpPort = http.toIntOrNull() ?: 8080, dest = DownloadMonitor.norm(dest))
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader(if (isNew) tr("Add PS4", "إضافة PS4") else tr("Edit PS4", "تعديل PS4"), nav)
        val full = Modifier.fillMaxWidth()
        OutlinedTextField(name, { name = it }, label = { Text(tr("Name (e.g. Living Room)", "الاسم (مثل غرفة المعيشة)")) }, singleLine = true, modifier = full)
        OutlinedTextField(host, { host = it }, label = { Text(tr("PS4 IP address / host", "عنوان IP للـPS4")) }, singleLine = true, modifier = full, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(http, { http = it }, label = { Text(tr("ezRemote web port (default 8080)", "منفذ ويب ezRemote (الافتراضي 8080)")) }, singleLine = true, modifier = full, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(dest, { dest = it }, label = { Text(tr("Default download folder", "مجلد التحميل الافتراضي")) }, singleLine = true, modifier = full)
        if (busy) LinearProgressIndicator(full)
        if (result.isNotEmpty()) Text(Tx.lines(result), fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && host.isNotBlank(), onClick = { busy = true; result = ""; scope.launch { result = DownloadMonitor.probe(build()); busy = false } }) { Lbl(tr("Test connection", "اختبار الاتصال")) }
            Button(enabled = host.isNotBlank(), onClick = { Ps4Repo.save(build()); nav.popBackStack() }) { Lbl(tr("Save", "حفظ")) }
        }
        if (!isNew) {
            val inUse = DownloadRepo.all.value.any { it.ps4Id == existing!!.id && it.state.active }
            OutlinedButton(enabled = !inUse, onClick = { Ps4Repo.delete(existing!!.id); nav.popBackStack() }) { Lbl(tr("Delete this PS4", "حذف هذا الجهاز"), color = MaterialTheme.colorScheme.error) }
            if (inUse) Dim(tr("Cannot delete while downloads are active on this PS4.", "لا يمكن الحذف أثناء وجود تحميلات نشطة على هذا الجهاز."))
        }
    }
}
