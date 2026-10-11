package com.abdo.ps4monitor
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Download engine. Evidence comes from ezRemote Server (port 6701, live) and, when it is not running, from bg_download_history.json.
 * Every download is identified by the exact file path this app asked for (folder + unique name), so there is no file guessing.
 *   HTTP 200 on download_url = queued only.  Server SUCCESS + final file present + valid PKG = completed.
 */
object DownloadMonitor {
    lateinit var app: Context
    val net = MutableStateFlow(true)
    val status = MutableStateFlow<Map<String, Ps4Status>>(emptyMap())
    val log = MutableStateFlow<List<String>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val loops = ConcurrentHashMap<String, Job>()
    private val rts = ConcurrentHashMap<String, Rt>()
    @Volatile private var restored = false
    private const val NOT_REGISTERED_MS = 20_000L
    private const val FAIL_GIVE_UP_MS = 240_000L

    private class Rt { var prevBytes = -1L; var prevT = 0L; var ema = 0.0; var lastGrow = 0L; var unchanged = 0; var failSince = 0L; var absent = 0; var iconTry = 0L; var relinks = 0; var lastRelink = 0L }

    // ---------------- log ----------------
    private var lastMsg = ""; private var rep = 0
    private fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    /** Technical log (Settings > Advanced). Identical consecutive lines are collapsed. Never pass credentials or full links. */
    fun d(m: String) {
        synchronized(this) {
            if (m == lastMsg) { rep++; return }
            if (rep > 0) log.update { (it + "[${stamp()}] (previous line repeated $rep more times)").takeLast(400) }
            rep = 0; lastMsg = m
            log.update { (it + "[${stamp()}] $m").takeLast(400) }
        }
    }
    fun clearLog() { log.value = emptyList() }

    fun init(c: Context) { app = c.applicationContext }
    fun kick() { wake.tryEmit(Unit) }
    fun startSvc() { runCatching { ContextCompat.startForegroundService(app, Intent(app, MonitorService::class.java)) } }
    fun norm(p: String): String { val t = p.trim().trimEnd('/'); return if (t.isEmpty()) "/" else if (t.startsWith("/")) t else "/$t" }
    private fun parent(path: String) = path.substringBeforeLast('/', "/").ifEmpty { "/" }
    private fun upStatus(id: String, f: (Ps4Status) -> Ps4Status) = status.update { m -> m + (id to f(m[id] ?: Ps4Status())) }
    fun isWatch(d: Download) = d.state.active && d.state != DlState.SUBMITTING
    private fun watched(ps4Id: String) = DownloadRepo.all.value.filter { it.ps4Id == ps4Id && isWatch(it) }

    // ---------------- start-up / recovery ----------------
    /** Re-attaches monitoring after a restart. Never sends a request to ezRemote. */
    fun restore() {
        if (restored) { ensureAll(); return }
        restored = true
        DownloadRepo.all.value.filter { it.state == DlState.SUBMITTING }.forEach {
            DownloadRepo.update(it.id) { x -> x.copy(state = DlState.FAILED, errorMessage = "The app closed while sending. The request was NOT repeated; check the PS4 or retry.", note = "") }
        }
        DownloadRepo.all.value.filter { it.state.active }.forEach { DownloadRepo.update(it.id) { x -> x.copy(speed = 0.0, etaSec = -1) } }
        if (DownloadRepo.all.value.any { isWatch(it) }) { d("Restored active downloads; re-attaching monitoring"); ensureAll(); startSvc() }
    }
    private fun ensureAll() = DownloadRepo.all.value.filter { isWatch(it) }.map { it.ps4Id }.distinct().forEach { ensureLoop(it) }
    private fun ensureLoop(ps4Id: String) {
        loops.compute(ps4Id) { _, j ->
            if (j != null && j.isActive) j else scope.launch {
                val me = currentCoroutineContext()[Job]
                try { loop(ps4Id) } catch (e: CancellationException) { throw e } catch (e: Exception) { d("Monitor loop error: ${e.javaClass.simpleName}: ${e.message}") }
                finally { if (me != null) loops.remove(ps4Id, me); if (watched(ps4Id).isNotEmpty()) ensureLoop(ps4Id) }
            }
        }
    }

    // ---------------- submit / retry ----------------
    private fun uniqueName(name: String, taken: Set<String>): String {
        fun busy(n: String) = n in taken || "$n.tmp" in taken
        if (!busy(name)) return name
        val stem = name.substringBeforeLast('.', name).replace(Regex(" \\(\\d+\\)$"), ""); val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
        var i = 2; while (busy("$stem ($i)$ext")) i++
        return "$stem ($i)$ext"
    }

    /**
     * ezRemote Server takes "dest" as the FINAL FILE PATH and writes "<dest>.tmp". So a folder + unique file name is always sent;
     * that path is then the exact identity of the download.
     */
    suspend fun submit(ps4: Ps4, url: String, destIn: String, fileNameIn: String?, retryOf: Download? = null): SubmitResult = withContext(Dispatchers.IO) {
        val u = url.trim()
        if (!Regex("^https?://\\S+$").matches(u)) return@withContext SubmitResult.Invalid("That is not a valid http(s) link.")
        if (ps4.host.isBlank()) return@withContext SubmitResult.Invalid("This PS4 has no IP address yet.")
        val dest = norm(destIn.ifBlank { ps4.dest })
        if (retryOf == null) DownloadRepo.all.value.firstOrNull { it.ps4Id == ps4.id && it.sourceUrl == u && it.state.active && !it.superseded }
            ?.let { return@withContext SubmitResult.Duplicate("This link is already being downloaded to ${ps4.name}.") }
        val taken = HashSet<String>()
        try { EzRemote.list(ps4, dest, 8000).forEach { taken += it.name } } catch (e: Exception) { }
        try { EzServer.list(ps4, 3000).filter { parent(it.path) == dest }.forEach { taken += it.path.substringAfterLast('/') } } catch (e: Exception) { }
        val name = uniqueName(Names.clean(fileNameIn ?: Names.fromUrl(u)).ifBlank { "download.pkg" }, taken)
        val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        DownloadRepo.add(Download(id, ps4.id, u, name, dest, name, (retryOf?.attempt ?: 0) + 1, createdAt = now, submittedAt = now, notificationId = Store.nextNotifId()))
        d("Download request submitted (${ps4.name})")
        when (val r = EzRemote.submit(ps4, u, joinPath(dest, name))) {
            is EzRemote.Submit.Rejected -> { DownloadRepo.remove(id); d("State: rejected — ${r.message}"); SubmitResult.Rejected(r.message) }
            is EzRemote.Submit.Unreachable -> { DownloadRepo.remove(id); d("State: not sent — ${r.message}"); SubmitResult.Unreachable(r.message) }
            EzRemote.Submit.Accepted -> {
                DownloadRepo.update(id) { it.copy(state = DlState.QUEUED, submittedAt = System.currentTimeMillis(), note = "ezRemote accepted the request. Waiting for the PS4 to start.") }
                d("State: QUEUED (request accepted; this is NOT proof that the download started)")
                ensureLoop(ps4.id); startSvc(); syncNotifs()
                SubmitResult.Accepted(id)
            }
        }
    }

    /** The server's live entry for [path] when the custom server (/v2 API) is running; null for an original server or when unreachable. */
    private fun v2Live(p: Ps4, path: String): SrvEntry? = try {
        if (EzServer.isV2(p, 4000)) EzServer.newest(EzServer.list(p, 4000), path)?.takeIf { it.v2 && it.id > 0 } else null
    } catch (e: Exception) { null }

    /** Custom server: a failed / "needs link" download continues when the link is sent again to the SAME file path (no restart from zero). */
    private suspend fun relink(old: Download, p: Ps4): SubmitResult? = withContext(Dispatchers.IO) {
        val path = old.path ?: return@withContext null
        val live = v2Live(p, path) ?: return@withContext null
        if (live.done) return@withContext null
        when (val r = EzRemote.submit(p, old.sourceUrl, path)) {
            is EzRemote.Submit.Rejected -> SubmitResult.Rejected(r.message)
            is EzRemote.Submit.Unreachable -> SubmitResult.Unreachable(r.message)
            EzRemote.Submit.Accepted -> {
                rts.remove(old.id)
                DownloadRepo.update(old.id) { it.copy(state = DlState.QUEUED, note = "Link sent again; the download continues from where it stopped.", errorMessage = null, terminalNotified = false, speed = 0.0, etaSec = -1) }
                d("${old.displayName}: link sent again to the same file (v2)")
                ensureLoop(p.id); startSvc(); syncNotifs()
                SubmitResult.Accepted(old.id)
            }
        }
    }

    /** Custom server: an expired link is refreshed by sending the original link again to the SAME file (up to 5 times in a row, 20 s apart). */
    private fun autoRelink(p: Ps4, d0: Download, rt: Rt, now: Long): Boolean {
        val path = d0.path ?: return false
        if (d0.sourceUrl.isBlank() || rt.relinks >= 5) return false
        if (now - rt.lastRelink < 20_000L) { setState(d0, DlState.STALLED, "Refreshing the expired link…"); return true }
        rt.lastRelink = now
        return when (EzRemote.submit(p, d0.sourceUrl, path)) {
            EzRemote.Submit.Accepted -> {
                rt.relinks++; rt.failSince = 0
                setState(d0, DlState.QUEUED, "The link expired and was refreshed automatically (${rt.relinks}/5).")
                d("${d0.displayName}: expired link refreshed automatically (${rt.relinks}/5)")
                true
            }
            else -> false
        }
    }

    /** The server stopped answering while downloads are unfinished: send the saved server file to the PS4 again (at most every 5 minutes). */
    private val healAt = ConcurrentHashMap<String, Long>()
    private fun tryHeal(p: Ps4, now: Long) {
        val f = ServerLoader.elfFile(app)
        if (!f.exists()) return
        if (now - (healAt[p.id] ?: 0L) < 300_000L) return
        healAt[p.id] = now
        d("ezRemote Server stopped answering: sending the saved server file to ${p.name} again")
        val err = ServerLoader.send(p.host, f) { }
        if (err != null) d("Re-sending the server failed: $err")
        else { EzServer.forget(p); d("Server file sent again; the server restarts and continues the downloads") }
    }

    /** Explicit user action: a NEW attempt with a new file name. The old record is kept and marked superseded. */
    suspend fun retry(id: String): SubmitResult {
        val old = DownloadRepo.get(id) ?: return SubmitResult.Invalid("Download not found.")
        val p = Ps4Repo.get(old.ps4Id) ?: return SubmitResult.Invalid("That PS4 profile no longer exists.")
        if (old.sourceUrl.isBlank()) return SubmitResult.Invalid("This download was found on the PS4, so there is no link to resend.")
        if (old.state != DlState.FAILED && old.state != DlState.NOT_STARTED) return SubmitResult.Invalid("Retry is only available for failed or not-started downloads.")
        relink(old, p)?.let { return it }
        val r = submit(p, old.sourceUrl, old.dest, old.fileName, old)
        if (r is SubmitResult.Accepted) { DownloadRepo.update(id) { it.copy(superseded = true) }; d("Retry created attempt ${old.attempt + 1}") }
        return r
    }

    // ---------------- polling loop ----------------
    private suspend fun loop(ps4Id: String) {
        var downSince = 0L
        while (currentCoroutineContext().isActive) {
            val p = Ps4Repo.get(ps4Id) ?: return
            val ds = watched(ps4Id); if (ds.isEmpty()) return
            val s = Store.settings()
            val srv: List<SrvEntry>? = try { EzServer.list(p, 3000).also { upStatus(p.id) { it.copy(web = Link.AVAILABLE, bg = Link.AVAILABLE) } } }
                catch (e: Exception) { upStatus(p.id) { it.copy(bg = Link.UNAVAILABLE) }; d("ezRemote Server (port 6701) not reachable: ${e.javaClass.simpleName}"); null }
            val hist = lazy { BgHistory.cached(p) }
            for (d0 in ds) {
                val x = DownloadRepo.get(d0.id) ?: continue
                if (isWatch(x)) try { step(p, x, srv, hist, s) } catch (e: CancellationException) { throw e } catch (e: Exception) { d("Step error: ${e.javaClass.simpleName}: ${e.message}") }
            }
            if (srv != null) { downSince = 0L; adopt(p, srv, null) }
            else if (downSince != 0L && System.currentTimeMillis() - downSince > 60_000L) tryHeal(p, System.currentTimeMillis())
            else { if (downSince == 0L) downSince = System.currentTimeMillis(); upStatus(p.id) { it.copy(web = if (hist.value != null) Link.AVAILABLE else Link.UNAVAILABLE) } }
            syncNotifs()
            val wait = if (srv != null) s.interval * 1000L else if (System.currentTimeMillis() - downSince > 300_000L) 30_000L else 10_000L
            withTimeoutOrNull(wait) { wake.first() }
        }
    }

    private fun setState(d0: Download, st: DlState, note: String, err: String? = null) {
        val old = DownloadRepo.get(d0.id) ?: return
        if (old.state == st && old.note == note && old.errorMessage == err) return
        if (old.state != st) d("${old.displayName}: ${old.state} → $st${if (note.isNotBlank()) " ($note)" else ""}")
        DownloadRepo.update(old.id) { it.copy(state = st, note = note, errorMessage = err,
            speed = if (st == DlState.DOWNLOADING) it.speed else 0.0, etaSec = if (st == DlState.DOWNLOADING) it.etaSec else -1,
            completedAt = if (st == DlState.COMPLETED) System.currentTimeMillis() else it.completedAt) }
    }
    private fun setExpected(d0: Download, size: Long) { val x = DownloadRepo.get(d0.id) ?: return; if (x.expectedSize != size) DownloadRepo.update(d0.id) { it.copy(expectedSize = size) } }

    private fun step(p: Ps4, d0: Download, srv: List<SrvEntry>?, hist: Lazy<List<BgEntry>?>, s: Settings) {
        val path = d0.path ?: return
        val rt = rts.getOrPut(d0.id) { Rt() }; val now = System.currentTimeMillis()
        val e = srv?.let { EzServer.newest(it, path) }
        if (e != null) {
            if (EzServer.sane(e.size)) setExpected(d0, e.size)
            when {
                e.done -> verify(p, d0, rt)
                e.pending -> { rt.failSince = 0; setState(d0, DlState.QUEUED, if (e.v2) "Queued on the server." else "Queued in ezRemote Server (it downloads one file at a time).") }
                e.paused -> { rt.failSince = 0; setState(d0, DlState.PAUSED, "Paused on the PS4 server.") }
                e.needsLink -> {
                    if (autoRelink(p, d0, rt, now)) return
                    setState(d0, DlState.FAILED, "", e.error.ifBlank { "The link no longer works." }); return
                }
                e.failed && e.v2 -> {
                    if (e.fatal) { setState(d0, DlState.FAILED, "", e.error.ifBlank { "The download failed." }); return }
                    // the server retries by itself, without a limit; show it as an interruption (an alert is raised if it lasts)
                    setState(d0, DlState.STALLED, "The server is retrying automatically (attempt ${e.attempts}): ${e.error}")
                }
                e.failed -> {
                    if (BgHistory.entry(hist.value, path)?.stopped == true) { setState(d0, DlState.PAUSED, "ezRemote Server stopped retrying after 5 failed attempts. You can resume the download."); return }
                    if (rt.failSince == 0L) { rt.failSince = now; d("${d0.displayName}: ezRemote Server reports FAILED (it retries up to 5 times)") }
                    if (now - rt.failSince < FAIL_GIVE_UP_MS) setState(d0, DlState.STALLED, "ezRemote Server reported a failure and retries automatically (up to 5 times).")
                    else setState(d0, DlState.FAILED, "", "ezRemote Server gave up after repeated failures. The link may be expired or invalid, or the host refused the request.")
                }
                else -> {
                    rt.failSince = 0
                    if (rt.relinks > 0 && now - rt.lastRelink > 300_000L) rt.relinks = 0     // it has been downloading again for 5 minutes
                    progress(p, d0, e.bytes, rt, now, s, e)
                }
            }
            return
        }
        if (srv != null) {                                   // reachable, but it has no entry for this download
            if (now - d0.submittedAt < NOT_REGISTERED_MS) { setState(d0, DlState.QUEUED, "Waiting for ezRemote Server to register the download…"); return }
            if ((try { EzRemote.fileSize(p, path, 5000) } catch (x: Exception) { 0L }) > 0) verify(p, d0, rt)
            else if (d0.startedAt == 0L) { setState(d0, DlState.NOT_STARTED, "ezRemote Server did not register this download.", "ezRemote Server did not register this download."); d("${d0.displayName}: not in ezRemote Server's list; NOT resubmitting") }
            else setState(d0, DlState.FAILED, "", "ezRemote Server no longer lists this download.")
            return
        }
        val h = BgHistory.entry(hist.value, path)           // server down: the history file still knows the download
        if (h != null) {
            if (EzServer.sane(h.size)) setExpected(d0, h.size)
            if (h.stopped) { setState(d0, DlState.PAUSED, "Paused: ezRemote Server is not running and this download is marked stopped."); return }
        }
        setState(d0, DlState.SERVER_DOWN, if (hist.value != null) "ezRemote Server is not running. Launch ezRemote on the PS4." else "The PS4 is not reachable.")
    }

    /** Speed from the server's byte counter (real growth only); stalled after [Settings.stuck] seconds without growth. */
    private fun progress(p: Ps4, d0: Download, bytes: Long, rt: Rt, now: Long, s: Settings, e: SrvEntry? = null) {
        var cur = 0.0
        if (rt.prevBytes >= 0 && now > rt.prevT) {
            if (bytes < rt.prevBytes) { rt.prevBytes = -1; rt.ema = 0.0 }                       // restarted from a smaller size
            else { cur = (bytes - rt.prevBytes) * 1000.0 / (now - rt.prevT); if (bytes > rt.prevBytes) { rt.lastGrow = now; rt.unchanged = 0 } else rt.unchanged++ }
        }
        if (rt.lastGrow == 0L) rt.lastGrow = now
        rt.ema = if (rt.prevBytes < 0) cur else rt.ema * 0.7 + cur * 0.3
        if (e != null && e.v2 && e.speed > 0) rt.ema = e.speed                              // the custom server measures its own speed
        rt.prevBytes = bytes; rt.prevT = now
        val exp = d0.expectedSize?.takeIf { it > 0 }
        val eta = if (exp != null && bytes < exp && rt.ema > 1.0) ((exp - bytes) / rt.ema).toLong() else -1L      // never a fake ETA
        val stalled = rt.unchanged >= 3 && now - rt.lastGrow >= s.stuck * 1000L
        val st = when { stalled -> DlState.STALLED; bytes == 0L -> DlState.QUEUED; else -> DlState.DOWNLOADING }
        val old = DownloadRepo.get(d0.id) ?: return
        if (old.state != st) d("${old.displayName}: ${old.state} → $st  ${Fmt.bytes(bytes)}")
        DownloadRepo.update(old.id) { it.copy(state = st, currentSize = bytes, speed = rt.ema, etaSec = eta, errorMessage = null,
            note = if (stalled) "No download progress detected for ${(now - rt.lastGrow) / 1000} s." else if (bytes == 0L) "Starting…" else if (e != null && e.v2 && e.conns > 0) "${e.conns} connection(s)" else "",
            startedAt = if (bytes > 0 && it.startedAt == 0L) now else it.startedAt) }
        if (!old.iconReady && bytes > (8L shl 20) && now - rt.iconTry > 60_000) { rt.iconTry = now; scope.launch { PkgThumbs.forDownload(old, 128); syncNotifs() } }
    }

    /** The server says SUCCESS. It also "succeeds" when the host answered with an error page, so the file itself is checked. */
    private fun verify(p: Ps4, d0: Download, rt: Rt) {
        val path = d0.path ?: return
        val size = try { EzRemote.fileSize(p, path, 5000) } catch (x: Exception) { setState(d0, DlState.VERIFYING, "Checking downloaded file…"); return }
        if (size <= 0) {
            rt.absent++
            val tmp = try { EzRemote.fileSize(p, "$path.tmp", 5000) } catch (x: Exception) { 0L }
            when {
                tmp > 0 && rt.absent >= 2 -> setState(d0, DlState.FAILED, "", "ezRemote Server finished but could not rename the temporary file (a folder with the same name may exist).")
                rt.absent >= 3 -> setState(d0, DlState.FAILED, "", "ezRemote Server reported success but the file is missing (it may have been deleted).")
                else -> setState(d0, DlState.VERIFYING, "Looking for the finished file…")
            }
            return
        }
        DownloadRepo.update(d0.id) { it.copy(currentSize = size) }
        if (path.lowercase().endsWith(".pkg")) {
            val isPkg = try { PkgInspector.readBytes(p, path, 0, 4).let { it.size == 4 && PkgFormat.u32(it, 0) == PkgFormat.MAGIC } } catch (x: Exception) { null }
            if (isPkg == false) { setState(d0, DlState.FAILED, "", "The finished file is not a PS4 PKG (size ${Fmt.bytes(size)}). The host probably returned an error page instead of the game."); return }
        }
        val exp = d0.expectedSize?.takeIf { it > 0 }
        if (exp != null && size < exp - maxOf(1L shl 20, exp / 1000)) { setState(d0, DlState.FAILED, "", "The finished file is ${Fmt.bytes(size)} but ${Fmt.bytes(exp)} was expected."); return }
        setState(d0, DlState.COMPLETED, "")
        d("${d0.displayName}: completed and verified (${Fmt.bytes(size)})")
    }

    // ---------------- pause / resume / delete (edits of bg_download_history.json) ----------------
    /**
     * ezRemote Server reads the history file only when it starts, so to PAUSE one download the server is stopped (it has no per-download
     * control: all its background downloads/installs stop), then the entry is marked FAILED with failed_attempts=5 (never retried).
     * The other downloads resume by themselves the next time ezRemote is launched on the PS4.
     */
    suspend fun pause(id: String): String = withContext(Dispatchers.IO) {
        val dl = DownloadRepo.get(id) ?: return@withContext tr("Download not found.", "التحميل غير موجود.")
        val p = Ps4Repo.get(dl.ps4Id) ?: return@withContext tr("That PS4 profile no longer exists.", "ملف هذا الـPS4 لم يعد موجودًا.")
        val path = dl.path ?: return@withContext tr("This download has no file path, so it cannot be paused.", "هذا التحميل بلا مسار ملف لذلك لا يمكن إيقافه مؤقتًا.")
        v2Live(p, path)?.let { live ->
            val c = EzServer.pause(p, live.id)
            if (!c.legacy) {
                if (!c.ok) return@withContext tr("Could not pause: ", "تعذّر الإيقاف المؤقت: ") + Tx.t(c.message)
                rts.remove(id)
                DownloadRepo.update(id) { it.copy(state = DlState.PAUSED, note = "Paused by you. Your other downloads keep running.", speed = 0.0, etaSec = -1) }
                syncNotifs(); d("${dl.displayName}: paused on the server (v2)")
                return@withContext tr("Paused. Your other downloads keep running.", "تم الإيقاف المؤقت. تحميلاتك الأخرى تواصل العمل.")
            }
        }
        if (EzServer.up(p) && !EzServer.stop(p)) return@withContext tr("ezRemote Server did not stop, so nothing was changed.", "لم يتوقف خادم ezRemote لذلك لم يتغير شيء.")
        val r = BgHistory.mark(p, path, 5)
        if (!r.ok) return@withContext tr("Could not pause: ", "تعذّر الإيقاف المؤقت: ") + Tx.t(r.message)
        rts.remove(id)
        DownloadRepo.update(id) { it.copy(state = DlState.PAUSED, note = "Paused by you. ezRemote Server was stopped and this download is marked stopped in bg_download_history.json.", speed = 0.0, etaSec = -1) }
        syncNotifs(); d("${dl.displayName}: paused (history entry marked failed_attempts=5)")
        tr("Paused. ezRemote Server was stopped; launch ezRemote on the PS4 to continue your other downloads. This one stays paused until you resume it.",
           "تم الإيقاف المؤقت. أُوقف خادم ezRemote؛ شغّل ezRemote على الـPS4 لمتابعة تحميلاتك الأخرى. هذا التحميل يبقى متوقفًا حتى تستأنفه.")
    }

    /** RESUME: failed_attempts back to 1 (state FAILED). The server continues from the .tmp size when it is started again. */
    suspend fun resume(id: String): String = withContext(Dispatchers.IO) {
        val dl = DownloadRepo.get(id) ?: return@withContext tr("Download not found.", "التحميل غير موجود.")
        val p = Ps4Repo.get(dl.ps4Id) ?: return@withContext tr("That PS4 profile no longer exists.", "ملف هذا الـPS4 لم يعد موجودًا.")
        val path = dl.path ?: return@withContext tr("This download has no file path.", "هذا التحميل بلا مسار ملف.")
        v2Live(p, path)?.let { live ->
            val c = EzServer.resume(p, live.id)
            if (!c.legacy) {
                if (!c.ok) return@withContext tr("Could not resume: ", "تعذّر الاستئناف: ") + Tx.t(c.message)
                rts.remove(id)
                DownloadRepo.update(id) { it.copy(state = DlState.QUEUED, note = "Resuming…", errorMessage = null, terminalNotified = false, speed = 0.0, etaSec = -1) }
                ensureLoop(p.id); startSvc(); syncNotifs(); d("${dl.displayName}: resumed on the server (v2)")
                return@withContext tr("Resuming. Nothing needs to be restarted on the PS4.", "جارٍ الاستئناف. لا حاجة لإعادة تشغيل أي شيء على الـPS4.")
            }
        }
        if (EzServer.up(p) && !EzServer.stop(p)) return@withContext tr("ezRemote Server did not stop, so nothing was changed.", "لم يتوقف خادم ezRemote لذلك لم يتغير شيء.")
        val r = BgHistory.mark(p, path, 1)
        if (!r.ok) return@withContext tr("Could not resume: ", "تعذّر الاستئناف: ") + Tx.t(r.message)
        rts.remove(id)
        DownloadRepo.update(id) { it.copy(state = DlState.SERVER_DOWN, note = "Waiting for ezRemote Server… launch ezRemote on the PS4 so it reloads the download list.", errorMessage = null, terminalNotified = false,
            speed = 0.0, etaSec = -1, startedAt = if (it.currentSize > 0 && it.startedAt == 0L) System.currentTimeMillis() else it.startedAt) }
        ensureLoop(p.id); startSvc()
        d("${dl.displayName}: resume prepared (history entry set to failed_attempts=1)")
        tr("Resume prepared. Launch ezRemote on the PS4 now so ezRemote Server reloads the list; the download continues from the partial file and this app will pick it up.",
           "تم تجهيز الاستئناف. شغّل ezRemote على الـPS4 الآن ليعيد خادم ezRemote تحميل القائمة؛ سيكمل التحميل من الملف الجزئي وسيلتقطه التطبيق.")
    }

    /**
     * DELETE: a running download is cancelled by stopping ezRemote Server and removing its entry from bg_download_history.json
     * (otherwise it would start again), then the partial / finished file is deleted. A finished download only needs the file step.
     */
    suspend fun delete(id: String, deleteFiles: Boolean): String = withContext(Dispatchers.IO) {
        val dl = DownloadRepo.get(id) ?: return@withContext tr("Download not found.", "التحميل غير موجود.")
        val p = Ps4Repo.get(dl.ps4Id); val path = dl.path
        val parts = ArrayList<String>()
        var v2Done = false
        if (p != null && path != null) {
            v2Live(p, path)?.let { live ->
                val c = EzServer.delete(p, live.id, deleteFiles)
                if (!c.legacy) {
                    if (!c.ok) return@withContext tr("Could not delete on the server: ", "تعذّر الحذف على الخادم: ") + Tx.t(c.message)
                    v2Done = true
                    // a running download needs a moment to stop writing: wait until the server no longer lists it
                    for (i in 0 until 12) {
                        val gone = try { EzServer.newest(EzServer.list(p, 3000), path)?.id != live.id } catch (e: Exception) { true }
                        if (gone) break
                        Thread.sleep(500)
                    }
                }
            }
        }
        if (p != null && path != null && dl.state != DlState.COMPLETED && !v2Done) {
            val live = try { EzServer.newest(EzServer.list(p, 3000), path) } catch (e: Exception) { null }
            if (live != null && (live.pending || live.active)) {
                if (!EzServer.stop(p)) return@withContext tr("ezRemote Server did not stop, so nothing was changed.", "لم يتوقف خادم ezRemote لذلك لم يتغير شيء.")
                parts += tr("ezRemote Server was stopped to cancel it; launch ezRemote on the PS4 to continue your other downloads.", "أُوقف خادم ezRemote لإلغائه؛ شغّل ezRemote على الـPS4 لمتابعة تحميلاتك الأخرى.")
            }
            val r = BgHistory.remove(p, path)
            if (!r.ok && dl.state.active) return@withContext tr("Could not remove it from bg_download_history.json (it would start again): ", "تعذّرت إزالته من bg_download_history.json (سيبدأ من جديد): ") + Tx.t(r.message)
            if (!r.ok) parts += tr("History file not updated: ", "لم يُحدَّث ملف السجل: ") + Tx.t(r.message)
        }
        if (deleteFiles && p != null && path != null) {
            val existing = listOf("$path.tmp", path).filter { f -> (try { EzRemote.fileSize(p, f, 5000) } catch (e: Exception) { 0L }) > 0 }
            if (existing.isNotEmpty()) {
                val r = try { EzRemote.remove(p, existing) } catch (e: Exception) { EzRemote.OpResult(false, EzRemote.friendly(e)) }
                val left = existing.filter { f -> (try { EzRemote.fileSize(p, f, 5000) } catch (e: Exception) { 1L }) > 0 }
                if (left.isNotEmpty()) { parts += tr("The file could not be deleted: ", "تعذّر حذف الملف: ") + Tx.t(r.message); return@withContext parts.joinToString("\n") }
                parts += tr("File deleted from the PS4.", "حُذف الملف من الـPS4.")
            }
        }
        if (p != null && path != null) Store.ignore(p.id, path)
        Notifier.cancel(dl.notificationId); rts.remove(id); PkgStore.delete("d:$id"); CoverColor.forget(id); DownloadRepo.remove(id)
        d("${dl.displayName}: deleted (files=$deleteFiles)")
        parts.joinToString("\n").ifBlank { tr("Removed from the list.", "أُزيل من القائمة.") }
    }

    // ---------------- discovery ----------------
    /** Downloads this app does not know (started in ezRemote itself, or stopped for good): exact path, real size and state. */
    private fun adopt(p: Ps4, srv: List<SrvEntry>?, hist: List<BgEntry>?) {
        data class C(val path: String, val size: Long, val bytes: Long, val stopped: Boolean)
        val cands = LinkedHashMap<String, C>()
        hist?.filter { it.state != 4 && it.dest.isNotBlank() }?.forEach { cands[it.dest] = C(it.dest, it.size, it.bytes, it.stopped) }
        srv?.filter { it.active || it.pending || it.paused || it.needsLink }?.forEach { cands[it.path] = C(it.path, it.size, it.bytes, it.paused || it.needsLink) }
        val known = DownloadRepo.all.value.filter { it.ps4Id == p.id }.mapNotNull { it.path }.toSet()
        var added = false
        for (c in cands.values.filter { it.path !in known && !Store.ignored(p.id, it.path) }) {
            val dir = parent(c.path); val name = c.path.substringAfterLast('/'); val now = System.currentTimeMillis()
            val have = maxOf(c.bytes, try { EzRemote.fileSize(p, c.path + ".tmp", 4000) } catch (e: Exception) { 0L })
            DownloadRepo.add(Download(UUID.randomUUID().toString(), p.id, "", name, dir, name, expectedSize = c.size.takeIf { EzServer.sane(it) }, currentSize = have,
                state = if (c.stopped) DlState.PAUSED else DlState.QUEUED, startedAt = if (have > 0) now else 0L,
                note = if (c.stopped) "ezRemote Server stopped retrying after 5 failed attempts. You can resume the download." else "Found in ezRemote Server (not started from this app).",
                createdAt = now, submittedAt = now, notificationId = Store.nextNotifId()))
            d("Adopted: $name"); added = true
        }
        if (added) { ensureLoop(p.id); startSvc() }
    }

    /** Connection test + discovery. Used by Home and the PS4 editor. */
    suspend fun probe(p: Ps4): String = withContext(Dispatchers.IO) {
        val s = Store.settings(); val parts = ArrayList<String>()
        var webOk = false; var srv: List<SrvEntry>? = null
        try { EzRemote.list(p, norm(p.dest), s.timeout * 1000); webOk = true; parts += "ezRemote web: OK" } catch (e: Exception) { d("Probe web: ${e.javaClass.simpleName}: ${e.message}"); parts += "ezRemote web: " + EzRemote.friendly(e) }
        try { srv = EzServer.list(p, 4000); parts += "ezRemote Server: OK" + if (EzServer.isV2Known(p)) " (custom build, /v2)" else "" } catch (e: Exception) { d("Probe server: ${e.javaClass.simpleName}"); parts += "ezRemote Server: not reachable (is it running? port 6701)" }
        upStatus(p.id) { it.copy(web = if (webOk) Link.AVAILABLE else Link.UNAVAILABLE, bg = if (srv != null) Link.AVAILABLE else Link.UNAVAILABLE) }
        val hist = if (webOk) try { BgHistory.read(p) } catch (e: Exception) { null } else null
        adopt(p, srv, hist)
        parts.joinToString("\n")
    }

    // ---------------- notifications (one stable id per download) ----------------
    fun syncNotifs() {
        for (x in DownloadRepo.all.value) {
            when {
                x.state == DlState.PAUSED -> Notifier.cancel(x.notificationId)
                !x.terminalNotified && (x.state == DlState.COMPLETED || x.state == DlState.FAILED || x.state == DlState.NOT_STARTED) -> {
                    DownloadRepo.update(x.id) { it.copy(terminalNotified = true) }       // persisted BEFORE posting: never twice
                    Notifier.result(x)
                }
                x.state.active && x.state != DlState.SUBMITTING -> Notifier.watchTrouble(x)
            }
        }
    }
}
