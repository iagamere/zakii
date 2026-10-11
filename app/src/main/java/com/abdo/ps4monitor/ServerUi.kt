@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Status of the server on the PS4, sending it (GoldHEN BinLoader), its download settings and its log. */
@Composable fun ServerScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val clip = LocalClipboardManager.current
    val activeId by Ps4Repo.activeId.collectAsState()
    val p = remember(activeId) { Ps4Repo.active() }
    var info by remember { mutableStateOf<EzServer.ServerInfo?>(null) }
    var checked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1) }
    var msg by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf<String?>(null) }
    val elf = remember { ServerLoader.elfFile(ctx) }
    var hasElf by remember { mutableStateOf(elf.exists()) }

    fun refresh() {
        val pp = p ?: return
        scope.launch { busy = true; info = withContext(Dispatchers.IO) { EzServer.info(pp) }; checked = true; busy = false }
    }
    LaunchedEffect(p?.id) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val err = withContext(Dispatchers.IO) { ServerLoader.importElf(ctx, uri) }
            if (err != null) msg = Tx.t(err)
            else { hasElf = true; msg = tr("File saved in the app. Tap \"Send to PS4\".", "حُفظ الملف داخل التطبيق. اضغط «إرسال إلى PS4».") }
        }
    }

    fun sendServer() {
        val pp = p ?: return
        scope.launch {
            busy = true; progress = 0; msg = ""
            val err = withContext(Dispatchers.IO) { ServerLoader.send(pp.host, elf) { v -> progress = v } }
            progress = -1
            if (err != null) { msg = tr("Could not send: ", "تعذّر الإرسال: ") + Tx.t(err); busy = false; return@launch }
            msg = tr("Sent. Waiting for the server to start…", "أُرسل. بانتظار تشغيل السيرفر…")
            EzServer.forget(pp)
            var found: EzServer.ServerInfo? = null
            for (i in 0 until 14) {
                delay(2000)
                val r = withContext(Dispatchers.IO) { EzServer.info(pp, 2000) }
                if (r != null && r.custom) { found = r; break }
            }
            info = found ?: withContext(Dispatchers.IO) { EzServer.info(pp) }
            msg = if (found != null) tr("The custom server is running (", "السيرفر المخصص يعمل (") + found.version + ")"
                  else tr("The PS4 received the file but the server did not answer. Watch the PS4 screen and check the log.", "استلم الـPS4 الملف لكن السيرفر لم يستجب. راقب شاشة الـPS4 وافحص السجل.")
            busy = false
        }
    }
    fun push(json: String) {
        val pp = p ?: return
        scope.launch {
            val c = withContext(Dispatchers.IO) { EzServer.updateSettings(pp, json) }
            if (!c.ok) msg = tr("Could not change the setting: ", "تعذّر تغيير الإعداد: ") + Tx.t(c.message)
            info = withContext(Dispatchers.IO) { EzServer.info(pp) }
        }
    }
    fun showLog() {
        val pp = p ?: return
        scope.launch {
            busy = true
            logText = withContext(Dispatchers.IO) {
                try { if (info?.custom == true) EzServer.log(pp, 80) else EzServer.logFile(pp) }
                catch (e: Exception) { try { EzServer.logFile(pp) } catch (e2: Exception) { EzRemote.friendly(e2) } }
            }
            busy = false
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackHeader(tr("ezRemote Server", "خادم ezRemote"), nav) { TextButton(onClick = { refresh() }, enabled = !busy) { Lbl(tr("Refresh", "تحديث")) } }
        if (p == null) { EmptyState(R.drawable.ic_console, tr("Add a PS4 first.", "أضف جهاز PS4 أولًا.")); return@Column }
        val i = info

        Panel {
            SectionTitle(tr("Status", "الحالة"))
            val line = when {
                !checked -> tr("Checking…", "جارٍ الفحص…")
                i == null -> tr("Not reachable on ${p.host}:6701", "غير متاح على ${p.host}:6701")
                i.custom -> tr("Custom server ", "السيرفر المخصص ") + i.version
                else -> tr("Original ezRemote Server (no per-download control)", "خادم ezRemote الأصلي (بلا تحكم بتحميل واحد)")
            }
            Text(line, fontWeight = FontWeight.SemiBold)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (msg.isNotEmpty()) Text(msg)
        }

        Panel {
            SectionTitle(tr("Send the server to the PS4", "إرسال السيرفر إلى الـPS4"))
            Dim(tr("Needs \"Enable BinLoader Server\" in GoldHEN settings (port 9090). Choose the .elf once; after that one tap sends it. Sending replaces the running ezRemote Server.",
                "يحتاج تفعيل «Enable BinLoader Server» في إعدادات GoldHEN (المنفذ 9090). اختر ملف .elf مرة واحدة، وبعدها ضغطة واحدة ترسله. الإرسال يستبدل خادم ezRemote العامل."))
            Dim(if (hasElf) tr("Saved file: ${elf.length() / 1024} KB. The app re-sends it by itself if the server stops while downloads are unfinished.",
                    "الملف المحفوظ: ${elf.length() / 1024} KB. يعيد التطبيق إرساله تلقائيًا إذا توقف السيرفر وفيه تحميلات غير مكتملة.")
                else tr("No file chosen yet.", "لم يُختر ملف بعد."))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Lbl(tr("Choose .elf file", "اختيار ملف .elf")) }
                Button(enabled = !busy && hasElf, onClick = { sendServer() }) { Lbl(tr("Send to PS4", "إرسال إلى PS4")) }
            }
            if (progress >= 0) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
        }

        if (i != null && i.custom) Panel {
            SectionTitle(tr("Download settings", "إعدادات التحميل"))
            Text(tr("Downloads at the same time", "التحميلات المتزامنة"))
            Chips(listOf(1, 2, 3, 4), i.maxParallel, "") { v -> push("{\"max_parallel\":$v}") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Choose connections automatically", "اختيار عدد الاتصالات تلقائيًا"))
                    Dim(tr("Adds connections while they really raise the speed", "يضيف اتصالات ما دامت ترفع السرعة فعلًا"))
                }
                Switch(checked = i.auto, onCheckedChange = { v -> push("{\"auto_conns\":$v}") })
            }
            if (i.auto) {
                Text(tr("Maximum connections per download", "أقصى عدد اتصالات للتحميل الواحد"))
                Chips(listOf(2, 4, 6, 8, 12), i.maxConns, "") { v -> push("{\"max_conns_per_download\":$v}") }
            } else {
                Text(tr("Connections per download", "الاتصالات لكل تحميل"))
                Chips(listOf(1, 2, 4, 6, 8), i.fixedConns, "") { v -> push("{\"fixed_conns\":$v}") }
            }
        }

        Panel {
            SectionTitle(tr("Server log", "سجل السيرفر"))
            Dim(tr("Shows what the server did (also works when it is not running, by reading its file from the PS4). Send it to me if downloads keep failing.",
                "يعرض ما فعله السيرفر (ويعمل حتى لو كان متوقفًا بقراءة ملفه من الـPS4). أرسله لي إن استمرت التحميلات في الفشل."))
            OutlinedButton(enabled = !busy, onClick = { showLog() }) { Lbl(tr("Show log", "عرض السجل")) }
        }
    }

    val lt = logText
    if (lt != null) AlertDialog(onDismissRequest = { logText = null }, title = { Text(tr("Server log", "سجل السيرفر")) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(lt, style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(onClick = { clip.setText(AnnotatedString(lt)); logText = null }) { Lbl(tr("Copy", "نسخ")) } },
        dismissButton = { TextButton(onClick = { logText = null }) { Lbl(tr("Close", "إغلاق")) } })
}
