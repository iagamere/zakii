package com.abdo.ps4monitor
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Channels:
 *  - "dl": ONE card per download (active or paused), in the style of a music player: media-style notification tinted with the game's cover colour,
 *    the cover as picture, name, progress, size, remaining size / time, speed, and two buttons: Pause <-> Resume, and Delete (asks for a second tap).
 *  - "monitor_min": the mandatory foreground-service notification, reduced to one quiet line (the cards above carry the details).
 *  - "alerts": a heads-up alert when a download has been interrupted for a while (one per incident, removed when it recovers).
 *  - "done": one notification per finished / failed download, grouped.
 * There is no per-download progress notification any more (that is what flooded the shade).
 */
object Notifier {
    private lateinit var ctx: Context
    private var lastSig = ""
    private val troubleSince = ConcurrentHashMap<Int, Long>()
    private val alerted = ConcurrentHashMap<Int, Long>()
    private const val ALERT_BASE = 500_000
    const val ACT_PAUSE = "com.abdo.ps4monitor.NOTIF_PAUSE"
    const val ACT_RESUME = "com.abdo.ps4monitor.NOTIF_RESUME"
    const val ACT_DELETE = "com.abdo.ps4monitor.NOTIF_DELETE"
    private const val CONFIRM_MS = 6_000L
    private val confirmDel = ConcurrentHashMap<String, Long>()     // download id -> time until which the next Delete tap is the confirmation
    private val posted = ConcurrentHashMap<Int, String>()          // notification id -> signature of the card currently shown

    fun init(c: Context) {
        ctx = c.applicationContext
        val m = ctx.getSystemService(NotificationManager::class.java)
        runCatching { m.deleteNotificationChannel("active"); m.deleteNotificationChannel("results") }     // channels of the old version
        runCatching { m.deleteNotificationChannel("monitor") }     // channel of 4.2 (its notification was the big combined one)
        m.createNotificationChannel(NotificationChannel("dl", tr("Downloads", "التحميلات"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        m.createNotificationChannel(NotificationChannel("monitor_min", tr("Monitoring (service)", "المراقبة (الخدمة)"), NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        m.createNotificationChannel(NotificationChannel("alerts", tr("Interruptions", "انقطاع التحميل"), NotificationManager.IMPORTANCE_HIGH))
        m.createNotificationChannel(NotificationChannel("done", tr("Finished downloads", "التحميلات المنتهية"), NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun pi() = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    private fun psName(d: Download) = Ps4Repo.get(d.ps4Id)?.name.orEmpty()
    private fun gameTitle(d: Download) = d.pkgTitle ?: d.displayName
    private fun cover(d: Download) = if (d.iconReady) PkgStore.bitmap("d:${d.id}", "icon0.png", 128) else null

    private fun activeList() = DownloadRepo.all.value.filter { it.state.active && it.state != DlState.SUBMITTING && !it.superseded }

    private fun oneLine(d: Download): String {
        val s = when (d.state) {
            DlState.DOWNLOADING -> listOfNotNull(d.pct?.let { "$it%" }, Fmt.mbs(d.speed)).joinToString(" • ")
            DlState.STALLED -> tr("stalled", "متعثّر")
            DlState.SERVER_DOWN -> tr("server not reachable", "الخادم غير متاح")
            DlState.QUEUED -> tr("queued", "في الانتظار")
            else -> d.state.label
        }
        return "${gameTitle(d).take(34)} — $s"
    }

    /** The mandatory foreground-service notification (id 1): one quiet line. The per-download cards carry the details. */
    fun summary(c: Context, fallback: String): Notification {
        val act = activeList()
        val b = NotificationCompat.Builder(c, "monitor_min").setSmallIcon(R.drawable.ic_stat_ps4).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).setContentIntent(pi()).setPriority(NotificationCompat.PRIORITY_MIN).setContentTitle("PS4 Monitor")
        if (act.isEmpty() || !Store.settings().notif) return b.setContentText(fallback).build()
        val speed = act.filter { it.state == DlState.DOWNLOADING }.sumOf { it.speed }
        val trouble = act.any { it.state == DlState.STALLED || it.state == DlState.SERVER_DOWN }
        val head = if (act.size == 1) tr("1 download", "تحميل واحد") else tr("${act.size} downloads", "${act.size} تحميلات")
        val line = listOfNotNull(head, if (speed > 0) Fmt.mbs(speed) else null, if (trouble) tr("interrupted - retrying", "منقطع - تجري إعادة المحاولة") else null).joinToString(" • ")
        return b.setContentText(line).build()
    }
    /** Re-posts the ongoing notification only when something visible changed. */
    fun refreshSummary(c: Context, fallback: String) {
        val sig = activeList().joinToString(";") { "${it.id}:${it.state}:${it.pct}:${(it.speed / 262144).toInt()}:${it.etaSec / 20}:${it.iconReady}" } + "|" + fallback + "|" + Store.settings().notif
        if (sig == lastSig) return
        lastSig = sig
        post(1, summary(c, fallback))
    }

    /** Call for every active download on each poll: raises ONE alert when the trouble lasted long enough. */
    fun watchTrouble(d: Download) {
        if (!Store.sp.getBoolean("alert_int", true) || !Store.settings().notif) return
        val id = d.notificationId; val now = System.currentTimeMillis()
        val bad = d.state == DlState.STALLED || d.state == DlState.SERVER_DOWN
        if (!bad) { troubleSince.remove(id); if (alerted.remove(id) != null) cancelAlert(id); return }
        val since = troubleSince.getOrPut(id) { now }
        val limit = if (d.state == DlState.SERVER_DOWN) 90_000L else 180_000L
        if (now - since < limit) return
        val last = alerted[id]
        if (last != null && now - last < 30 * 60_000L) return          // remind at most every 30 minutes
        alerted[id] = now
        post(id + ALERT_BASE, alertNotification(d, now - since))
    }
    private fun alertNotification(d: Download, trouble: Long): Notification {
        val mins = (trouble / 60_000L).coerceAtLeast(1L)
        val why = if (d.state == DlState.SERVER_DOWN)
            tr("ezRemote Server on the PS4 is not answering (the PS4 may be asleep, or the server stopped).", "خادم ezRemote على الـPS4 لا يستجيب (قد يكون الجهاز في وضع الراحة أو توقف الخادم).")
        else tr("No download progress is being made.", "لا يوجد تقدّم في التحميل.")
        val at = listOfNotNull(d.pct?.let { "$it%" }, if (d.currentSize > 0) Fmt.gb(d.currentSize) else null).joinToString(" • ")
        val body = why + "\n" + tr("Interrupted for $mins min", "منقطع منذ $mins د") + (if (at.isNotEmpty()) " • $at" else "") + "\n" + tr("Tap to open the app.", "اضغط لفتح التطبيق.")
        val b = NotificationCompat.Builder(ctx, "alerts").setSmallIcon(R.drawable.ic_stat_ps4)
            .setContentTitle(tr("Download interrupted", "انقطع التحميل") + ": " + gameTitle(d)).setContentText(why)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setSubText(psName(d).ifEmpty { null })
            .setAutoCancel(true).setContentIntent(pi()).setCategory(NotificationCompat.CATEGORY_ERROR).setPriority(NotificationCompat.PRIORITY_HIGH)
        cover(d)?.let { b.setLargeIcon(it) }
        CoverColor.forDownload(d)?.let { b.setColor(it) }
        return b.build()
    }

    /** Completed / failed / not started: posted once (guarded by Download.terminalNotified), grouped. */
    fun result(d: Download) {
        clearTrouble(d.notificationId)
        if (!Store.settings().notif) return
        val ok = d.state == DlState.COMPLETED
        val secs = if (d.startedAt > 0 && d.completedAt > d.startedAt) (d.completedAt - d.startedAt) / 1000L else 0L
        val head = when {
            ok -> tr("Download completed", "اكتمل التحميل")
            d.state == DlState.FAILED -> tr("Download failed", "فشل التحميل")
            else -> tr("Download has not started yet", "لم يبدأ التحميل بعد")
        }
        val body = when {
            ok -> listOfNotNull(Fmt.gb(d.currentSize), if (secs > 0) tr("in ${Fmt.dur(secs)}", "خلال ${Fmt.dur(secs)}") else null, tr("verified", "تم التحقق")).joinToString(" • ")
            d.state == DlState.FAILED -> Tx.t(d.errorMessage ?: tr("Unknown reason", "سبب غير معروف"))
            else -> tr("ezRemote accepted the request but no download activity was detected.", "قبل ezRemote الطلب لكن لم يُكتشف أي نشاط تحميل.")
        }
        val b = NotificationCompat.Builder(ctx, "done").setSmallIcon(R.drawable.ic_stat_ps4).setGroup("ps4_done")
            .setContentTitle(gameTitle(d)).setContentText("$head • $body").setSubText(psName(d).ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText("$head\n$body")).setAutoCancel(true).setContentIntent(pi())
        cover(d)?.let { b.setLargeIcon(it) }
        CoverColor.forDownload(d)?.let { b.setColor(it) }
        post(d.notificationId, b.build())
        post(2, NotificationCompat.Builder(ctx, "done").setSmallIcon(R.drawable.ic_stat_ps4).setGroup("ps4_done").setGroupSummary(true)
            .setContentTitle(tr("PS4 downloads", "تحميلات PS4")).setAutoCancel(true).setContentIntent(pi()).build())
    }

    private fun cancelAlert(id: Int) { runCatching { NotificationManagerCompat.from(ctx).cancel(id + ALERT_BASE) } }
    fun clearTrouble(id: Int) { troubleSince.remove(id); if (alerted.remove(id) != null) cancelAlert(id) }
    fun cancel(id: Int) { clearTrouble(id); posted.remove(id); runCatching { NotificationManagerCompat.from(ctx).cancel(id) } }

    // ---------------------------------------------------------------------------------------------
    // Download cards (music-player style)
    // ---------------------------------------------------------------------------------------------
    private fun cardWanted(d: Download) = !d.superseded && d.state != DlState.SUBMITTING && (d.state.active || d.state == DlState.PAUSED)
    private fun confirming(id: String) = (confirmDel[id] ?: 0L) > System.currentTimeMillis()

    /** No cover yet: a stable colour taken from the file name, so the card is still coloured. */
    private fun fallbackColor(d: Download): Int =
        Color.HSVToColor(floatArrayOf(((d.displayName.hashCode() and 0x7fffffff) % 360).toFloat(), 0.55f, 0.55f))

    private fun bar(fr: Float): String {
        val n = 10
        val f = (fr * n).toInt().coerceIn(0, n)
        return "■".repeat(f) + "□".repeat(n - f)
    }

    /** line under the title, and the line in the header */
    private fun cardTexts(d: Download): Pair<String, String> {
        val total = d.expectedSize ?: 0L
        val fr = d.frac
        val left = if (total > 0L) (total - d.currentSize).coerceAtLeast(0L) else 0L
        val size = if (total > 0L) "${Fmt.bytes(d.currentSize)} / ${Fmt.bytes(total)}" else Fmt.bytes(d.currentSize)
        val text = listOfNotNull(if (fr != null) "${bar(fr)} ${d.pct}%" else null, size).joinToString(" • ")
        val leftTxt = if (left > 0L) "${Fmt.bytes(left)} ${tr("left", "متبقٍ")}" else null
        val sub = when (d.state) {
            DlState.DOWNLOADING -> listOfNotNull(leftTxt, if (d.etaSec >= 0) Fmt.dur(d.etaSec) else null, if (d.speed > 0) Fmt.mbs(d.speed) else null).joinToString(" • ")
            DlState.PAUSED -> listOfNotNull(tr("Paused", "متوقف مؤقتًا"), leftTxt).joinToString(" • ")
            else -> listOfNotNull(d.state.label, leftTxt).joinToString(" • ")
        }
        return text to sub
    }

    private fun actionPi(act: String, d: Download): PendingIntent {
        // PendingIntents are told apart by action / data, not by extras: the id goes into the data so two downloads never share one
        val i = Intent(ctx, NotifActionReceiver::class.java).setAction(act).setData(Uri.parse("ps4mon://download/${d.id}/$act")).putExtra("id", d.id)
        return PendingIntent.getBroadcast(ctx, d.notificationId * 4 + (if (act == ACT_DELETE) 1 else 0), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun cardAction(icon: Int, title: String, pi: PendingIntent) = Notification.Action.Builder(Icon.createWithResource(ctx, icon), title, pi).build()

    private fun card(d: Download): Notification {
        val paused = d.state == DlState.PAUSED
        val confirm = confirming(d.id)
        val (text0, sub) = cardTexts(d)
        val text = if (confirm) tr("Tap the bin again to delete this download and its file", "اضغط سلة المهملات مرة أخرى لحذف التحميل وملفه") else text0
        val b = Notification.Builder(ctx, "dl")
            .setSmallIcon(R.drawable.ic_stat_ps4)
            .setContentTitle(gameTitle(d)).setContentText(text).setSubText(sub)
            .setOngoing(!paused).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(pi())
            .setColor(CoverColor.forDownload(d) ?: fallbackColor(d)).setColorized(true)
            .addAction(if (paused) cardAction(R.drawable.ic_play, tr("Resume", "استئناف"), actionPi(ACT_RESUME, d))
                       else cardAction(R.drawable.ic_pause, tr("Pause", "إيقاف مؤقت"), actionPi(ACT_PAUSE, d)))
            .addAction(if (confirm) cardAction(R.drawable.ic_warning, tr("Confirm delete", "تأكيد الحذف"), actionPi(ACT_DELETE, d))
                       else cardAction(R.drawable.ic_delete, tr("Delete", "حذف"), actionPi(ACT_DELETE, d)))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1))
        cover(d)?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun cardSig(d: Download) =
        "${d.state}:${d.pct}:${d.currentSize shr 26}:${(d.speed / 262144).toInt()}:${d.etaSec / 20}:${d.iconReady}:${d.pkgTitle}:${confirming(d.id)}:${Lang.isAr}"

    /** Posts / updates / removes the cards. Cheap: a card is re-posted only when something visible changed. */
    fun refreshDownloads() {
        if (!::ctx.isInitialized) return
        val all = DownloadRepo.all.value
        if (!Store.settings().notif) { posted.keys.toList().forEach { cancel(it) }; return }
        val ids = HashSet<Int>()
        for (d in all) {
            if (!cardWanted(d)) continue
            ids += d.notificationId
            val sig = cardSig(d)
            if (posted[d.notificationId] == sig) continue
            posted[d.notificationId] = sig
            post(d.notificationId, card(d))
        }
        for (id in posted.keys.toList()) {
            if (id in ids) continue
            posted.remove(id)
            // a finished download's card was already replaced by the result notification (same id); only remove cards that have no owner
            val owner = all.firstOrNull { it.notificationId == id }
            if (owner == null || owner.superseded) runCatching { NotificationManagerCompat.from(ctx).cancel(id) }
        }
    }

    /** Buttons of a card (called by [NotifActionReceiver]). Same operations as the buttons in the app. */
    fun onAction(action: String, id: String) {
        if (!::ctx.isInitialized) return
        if (DownloadRepo.get(id) == null) return
        when (action) {
            ACT_PAUSE -> Ops.launch(tr("Pausing…", "إيقاف مؤقت…")) { DownloadMonitor.pause(id) }
            ACT_RESUME -> Ops.launch(tr("Preparing resume…", "تجهيز الاستئناف…")) { DownloadMonitor.resume(id) }
            ACT_DELETE -> {
                if (confirming(id)) {
                    confirmDel.remove(id)
                    Ops.launch(tr("Deleting…", "جارٍ الحذف…")) { DownloadMonitor.delete(id, true) }
                } else {
                    confirmDel[id] = System.currentTimeMillis() + CONFIRM_MS
                    refreshDownloads()
                    Handler(Looper.getMainLooper()).postDelayed({ refreshDownloads() }, CONFIRM_MS + 300L)
                }
            }
        }
    }

    private fun post(id: Int, n: Notification) {
        if (!::ctx.isInitialized) return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }
}
