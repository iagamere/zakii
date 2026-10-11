package com.abdo.ps4monitor
import java.util.UUID

/** One PS4. Everything goes through ezRemote's web server (default 8080) and ezRemote Server (port 6701); no FTP. */
data class Ps4(val id: String = UUID.randomUUID().toString(), val name: String, val host: String, val httpPort: Int = 8080, val dest: String = "/data/pkg")

enum class Link(private val en: String, private val ar: String) {
    UNKNOWN("Not checked", "لم يُفحص"), AVAILABLE("Available", "متاح"), UNAVAILABLE("Unavailable", "غير متاح");
    val label get() = tr(en, ar)
}
data class Ps4Status(val web: Link = Link.UNKNOWN, val bg: Link = Link.UNKNOWN)

enum class DlState(private val en: String, private val ar: String, val active: Boolean) {
    SUBMITTING("Sending to PS4", "جارٍ الإرسال إلى الـPS4", true),
    QUEUED("Queued", "في قائمة الانتظار", true),
    DOWNLOADING("Downloading", "جارٍ التحميل", true),
    STALLED("Stalled", "متعثّر", true),
    SERVER_DOWN("ezRemote Server not reachable", "خادم ezRemote غير متاح", true),
    VERIFYING("Verifying", "جارٍ التحقق", true),
    PAUSED("Paused", "متوقف مؤقتًا", false),
    COMPLETED("Completed", "اكتمل", false),
    FAILED("Failed", "فشل", false),
    NOT_STARTED("Download not started", "لم يبدأ التحميل", false);
    val label get() = tr(en, ar)
    companion object {
        /** Reads names saved by older versions. */
        fun parse(s: String, hasPath: Boolean): DlState = when (s) {
            "WAITING_FOR_START", "STARTING" -> QUEUED
            "CONNECTION_LOST" -> SERVER_DOWN
            "STOPPED" -> if (hasPath) PAUSED else FAILED
            else -> runCatching { valueOf(s) }.getOrDefault(FAILED)
        }
    }
}

data class Download(
    val id: String, val ps4Id: String, val sourceUrl: String, val displayName: String, val dest: String, val fileName: String?,
    val attempt: Int = 1, val superseded: Boolean = false,
    val expectedSize: Long? = null, val currentSize: Long = 0, val speed: Double = 0.0, val etaSec: Long = -1,
    val state: DlState = DlState.SUBMITTING, val note: String = "", val errorMessage: String? = null,
    val createdAt: Long, val submittedAt: Long = 0, val startedAt: Long = 0, val completedAt: Long = 0,
    val notificationId: Int, val terminalNotified: Boolean = false,
    val pkgTitle: String? = null, val titleId: String? = null, val iconReady: Boolean = false,
    val updatedAt: Long = createdAt
) {
    /** Final file path on the PS4 = the "dest" ezRemote Server received; the data is written to "<path>.tmp" until it finishes. */
    val path: String? get() = fileName?.let { joinPath(dest, it) }
    val tmpPath: String? get() = path?.plus(".tmp")
    val pct: Int? get() = expectedSize?.takeIf { it > 0 }?.let { (currentSize * 100 / it).toInt().coerceIn(0, 100) }
    val frac: Float? get() = expectedSize?.takeIf { it > 0 }?.let { (currentSize.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

data class FsEntry(val name: String, val size: Long, val mtime: Long = 0, val isDir: Boolean = false)

sealed class SubmitResult {
    data class Accepted(val id: String) : SubmitResult()
    data class Rejected(val message: String) : SubmitResult()
    data class Unreachable(val message: String) : SubmitResult()
    data class Duplicate(val message: String) : SubmitResult()
    data class Invalid(val message: String) : SubmitResult()
}
