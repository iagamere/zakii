package com.abdo.ps4monitor
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Three channels:
 *  - "monitor": ONE ongoing notification (the foreground service's) that lists every active download and is tinted with the cover colour.
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

    fun init(c: Context) {
        ctx = c.applicationContext
        val m = ctx.getSystemService(NotificationManager::class.java)
        runCatching { m.deleteNotificationChannel("active"); m.deleteNotificationChannel("results") }     // channels of the old version
        m.createNotificationChannel(NotificationChannel("monitor", tr("Download progress", "تقدّم التحميل"), NotificationManager.IMPORTANCE_LOW))
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

    /** The mandatory foreground-service notification (id 1), rich: every active download, overall progress, cover colour. */
    fun summary(c: Context, fallback: String): Notification {
        val act = activeList()
        val b = NotificationCompat.Builder(c, "monitor").setSmallIcon(R.drawable.ic_stat_ps4).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).setContentIntent(pi())
        if (act.isEmpty() || !Store.settings().notif) return b.setContentTitle("PS4 Monitor").setContentText(fallback).build()
        val primary = act.firstOrNull { it.state == DlState.DOWNLOADING } ?: act.first()
        val speed = act.filter { it.state == DlState.DOWNLOADING }.sumOf { it.speed }
        val trouble = act.any { it.state == DlState.STALLED || it.state == DlState.SERVER_DOWN }
        val known = act.filter { (it.expectedSize ?: 0L) > 0L }
        val pct = if (known.isNotEmpty()) (known.sumOf { it.currentSize } * 100L / known.sumOf { it.expectedSize ?: 1L }).toInt().coerceIn(0, 100) else null
        val head = if (act.size == 1) gameTitle(primary) else tr("${act.size} downloads", "${act.size} تحميلات")
        val eta = if (act.size == 1 && primary.etaSec >= 0) "ETA ${Fmt.dur(primary.etaSec)}" else null
        val line = listOfNotNull(pct?.let { "$it%" }, if (speed > 0) Fmt.mbs(speed) else null, eta).joinToString(" • ")
        val warn = tr("Interrupted - retrying", "منقطع - تجري إعادة المحاولة")
        b.setContentTitle(head).setContentText(if (trouble) (if (line.isEmpty()) warn else "$line • $warn") else line.ifEmpty { fallback })
        val inbox = NotificationCompat.InboxStyle()
        act.take(5).forEach { inbox.addLine(oneLine(it)) }
        if (act.size > 5) inbox.addLine("+${act.size - 5}")
        psName(primary).ifEmpty { null }?.let { inbox.setSummaryText(it) }
        b.setStyle(inbox)
        if (pct != null) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        cover(primary)?.let { b.setLargeIcon(it) }
        CoverColor.forDownload(primary)?.let { b.setColor(it); b.setColorized(true) }
        return b.build()
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
    fun cancel(id: Int) { clearTrouble(id); runCatching { NotificationManagerCompat.from(ctx).cancel(id) } }

    private fun post(id: Int, n: Notification) {
        if (!::ctx.isInitialized) return
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }
}
