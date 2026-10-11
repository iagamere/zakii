package com.abdo.ps4monitor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** 0 = follow phone, 1 = English, 2 = Arabic. Observable: changing it re-composes the whole UI instantly. */
object Lang {
    var mode by mutableIntStateOf(0)
    val isAr: Boolean get() = when (mode) { 1 -> false; 2 -> true; else -> Locale.getDefault().language == "ar" }
}
fun tr(en: String, ar: String) = if (Lang.isAr) ar else en

/**
 * Translates the English status/error sentences produced by the monitor engine at DISPLAY time.
 * Records keep their original English text, so switching language also re-translates old downloads.
 */
object Tx {
    private val m = mapOf(
        "Starting…" to "جارٍ البدء…",
        "ezRemote Server is not running. Launch ezRemote on the PS4." to "خادم ezRemote لا يعمل. شغّل ezRemote على الـPS4.",
        "The PS4 is not reachable." to "الـPS4 غير متاح.",
        "Queued in ezRemote Server (it downloads one file at a time)." to "في قائمة انتظار خادم ezRemote (يحمّل ملفًا واحدًا في كل مرة).",
        "ezRemote Server no longer lists this download." to "لم يعد خادم ezRemote يعرض هذا التحميل.",
        "Old record: it cannot be followed by file path any more." to "سجل قديم: لم يعد ممكنًا تتبعه بمسار الملف.",
        "The app closed while sending. The request was NOT repeated; check the PS4 or retry." to "أُغلق التطبيق أثناء الإرسال. لم يُعَد إرسال الطلب؛ تحقق من الـPS4 أو أعد المحاولة.",
        "ezRemote accepted the request. Waiting for the PS4 to start." to "قبل ezRemote الطلب. بانتظار أن يبدأ الـPS4 التحميل.",
        "That is not a valid http(s) link." to "هذا ليس رابط http(s) صالحًا.",
        "This PS4 has no IP address yet." to "لا يوجد عنوان IP لهذا الـPS4.",
        "Download not found." to "التحميل غير موجود.",
        "That PS4 profile no longer exists." to "ملف هذا الـPS4 لم يعد موجودًا.",
        "Retry is only available for failed or not-started downloads." to "إعادة المحاولة متاحة فقط للتحميلات الفاشلة أو التي لم تبدأ.",
        "ezRemote accepted the request but no download activity was detected." to "قبل ezRemote الطلب لكن لم يُكتشف أي نشاط تحميل.",
        "Looking for the finished file…" to "جارٍ البحث عن الملف المكتمل…",
        "Checking downloaded file…" to "جارٍ فحص الملف المحمّل…",
        "PKG header (agrees with PFS image end)" to "ترويسة PKG (تطابق نهاية صورة PFS)",
        "PKG header" to "ترويسة PKG",
        "PKG header (unconfirmed)" to "ترويسة PKG (غير مؤكد)",
        "ezRemote Server reported a failure and retries automatically (up to 5 times)." to "أبلغ خادم ezRemote عن فشل ويعيد المحاولة تلقائيًا (حتى 5 مرات).",
        "ezRemote Server gave up after repeated failures. The link may be expired or invalid, or the host refused the request." to "استسلم خادم ezRemote بعد فشل متكرر. قد يكون الرابط منتهيًا أو غير صالح، أو رفض الموقع الطلب.",
        "ezRemote Server did not register this download." to "لم يسجّل خادم ezRemote هذا التحميل.",
        "Waiting for ezRemote Server to register the download…" to "بانتظار أن يسجّل خادم ezRemote التحميل…",
        "ezRemote Server reported success but the file is missing (it may have been deleted)." to "أبلغ خادم ezRemote بالنجاح لكن الملف مفقود (ربما حُذف).",
        "Found in ezRemote Server (not started from this app)." to "وُجد في خادم ezRemote (لم يبدأ من هذا التطبيق).",
        "ezRemote could not hand the download to ezRemote Server (port 6701). The background server may not be running: restart ezRemote Client on the PS4." to "تعذّر على ezRemote تسليم التحميل إلى خادم ezRemote (المنفذ 6701). قد لا يكون الخادم يعمل: أعد تشغيل ezRemote Client على الـPS4.",
        "ezRemote Server" to "خادم ezRemote",
        "not reachable (is it running? port 6701)" to "غير متاح (هل يعمل؟ المنفذ 6701)",
        "ezRemote Server stopped retrying after 5 failed attempts. You can resume the download." to "توقف خادم ezRemote عن المحاولة بعد 5 محاولات فاشلة. يمكنك استئناف التحميل.",
        "Paused by you. ezRemote Server was stopped and this download is marked stopped in bg_download_history.json." to "أوقفته مؤقتًا. أُوقف خادم ezRemote وعُلِّم هذا التحميل كمتوقف في bg_download_history.json.",
        "Waiting for ezRemote Server… launch ezRemote on the PS4 so it reloads the download list." to "بانتظار خادم ezRemote… شغّل ezRemote على الـPS4 ليعيد تحميل قائمة التحميلات.",
        "Paused: ezRemote Server is not running and this download is marked stopped." to "متوقف مؤقتًا: خادم ezRemote لا يعمل وهذا التحميل معلَّم كمتوقف.",
        "This download is not listed in bg_download_history.json." to "هذا التحميل غير مدرج في bg_download_history.json.",
        "The edited file would not be valid JSON; nothing was written." to "الملف المعدَّل لن يكون JSON صالحًا؛ لم يُكتب شيء.",
        "The PS4 file did not keep the change." to "لم يحتفظ ملف الـPS4 بالتغيير.",
        "known" to "معروف",
        "Queued on the server." to "في قائمة الانتظار على الخادم.",
        "Refreshing the expired link…" to "جارٍ تجديد الرابط المنتهي…",
        "Paused on the PS4 server." to "متوقف مؤقتًا على خادم الـPS4.",
        "The link no longer works." to "الرابط لم يعد يعمل.",
        "The download failed." to "فشل التحميل.",
        "Paused by you. Your other downloads keep running." to "أوقفته أنت. تحميلاتك الأخرى تواصل العمل.",
        "Resuming…" to "جارٍ الاستئناف…",
        "Link sent again; the download continues from where it stopped." to "أُعيد إرسال الرابط؛ سيكمل التحميل من حيث توقف.",
        "The link returned a web page instead of a file (expired link, quota exceeded or login page)" to "أعاد الرابط صفحة ويب بدل الملف (رابط منتهٍ أو تجاوز الحصة أو صفحة دخول).",
        "Disk write failed (disk full or storage error)" to "فشلت الكتابة على القرص (القرص ممتلئ أو خطأ في التخزين).",
        "No data for 45 seconds" to "لا بيانات منذ 45 ثانية",
        "The PS4 did not answer in time." to "لم يستجب الـPS4 في الوقت المناسب.",
        "Cannot reach the PS4 web server. Is ezRemote running with its web server enabled?" to "تعذّر الوصول إلى خادم الويب في الـPS4. هل ezRemote يعمل وخادم الويب مفعّل؟",
        "PS4 address not found." to "عنوان الـPS4 غير موجود.",
        "PS4 web connection problem." to "مشكلة في اتصال ويب الـPS4.",
        "Timeout" to "انتهت المهلة",
        "ezRemote web" to "ويب ezRemote",
        "OK" to "يعمل",
    )
    private val pats: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("This link is already being downloaded to (.*)\\.") to { r -> "هذا الرابط قيد التحميل بالفعل إلى ${r.groupValues[1]}." },
        Regex("No download progress detected for (\\d+) s\\.") to { r -> "لم يُكتشف تقدم في التحميل منذ ${r.groupValues[1]} ثانية." },
        Regex("The finished file is (.*) but (.*) was expected\\.") to { r -> "حجم الملف المكتمل ${r.groupValues[1]} لكن المتوقع ${r.groupValues[2]}." },
        Regex("The finished file is not a PS4 PKG \\(size (.*)\\)\\. .*") to { r -> "الملف المكتمل ليس PKG للـPS4 (الحجم ${r.groupValues[1]}). غالبًا أعاد الموقع صفحة خطأ بدل اللعبة." },
        Regex("ezRemote answered HTTP (\\d+)\\.") to { r -> "أجاب ezRemote برمز HTTP ${r.groupValues[1]}." },
        Regex("ezRemote Server answered HTTP (\\d+)\\.") to { r -> "أجاب خادم ezRemote برمز HTTP ${r.groupValues[1]}." },
        Regex("The server is retrying automatically \\(attempt (\\d+)\\): (.*)") to { r -> "الخادم يعيد المحاولة تلقائيًا (المحاولة ${r.groupValues[1]}): ${t(r.groupValues[2])}" },
        Regex("The link expired and was refreshed automatically \\((\\d+)/(\\d+)\\)\\.") to { r -> "انتهى الرابط وجُدِّد تلقائيًا (${r.groupValues[1]}/${r.groupValues[2]})." },
        Regex("(\\d+) connection\\(s\\)") to { r -> "${r.groupValues[1]} اتصال" },
        Regex("The server will retry automatically \\((\\d+)/(\\d+)\\): (.*)") to { r -> "سيعيد الخادم المحاولة تلقائيًا (${r.groupValues[1]}/${r.groupValues[2]}): ${t(r.groupValues[3])}" },
        Regex("HTTP (\\d+): the link is expired or forbidden\\. .*") to { r -> "HTTP ${r.groupValues[1]}: الرابط منتهٍ أو ممنوع. أعد إرسال الرابط (بنفس الوجهة) فيكمل التحميل." },
        Regex("The link no longer works: (.*)\\. Send the link again .*") to { r -> "الرابط لم يعد يعمل: ${r.groupValues[1]}. أعد إرسال الرابط (بنفس الوجهة) فيكمل التحميل." },
        Regex("Not enough free space: need (.*), free (.*)") to { r -> "لا توجد مساحة كافية: المطلوب ${r.groupValues[1]} والمتاح ${r.groupValues[2]}." },
        Regex("ezRemote rejected the request\\.") to { _ -> "رفض ezRemote الطلب." },
        Regex("ezRemote rejected the request: (.*)") to { r -> "رفض ezRemote الطلب: ${r.groupValues[1]}" },
    )
    fun t(s: String): String {
        if (!Lang.isAr || s.isBlank()) return s
        m[s]?.let { return it }
        for ((r, f) in pats) r.matchEntire(s)?.let { return f(it) }
        val i = s.indexOf(": ")
        if (i > 0) { val l = s.substring(0, i); val r = s.substring(i + 2); val a = t(l); val b = t(r); if (a != l || b != r) return "$a: $b" }
        return s
    }
    fun lines(s: String) = if (!Lang.isAr) s else s.lines().joinToString("\n") { t(it) }
    fun ev(s: String): String { if (!Lang.isAr) return s; val i = s.indexOf("  "); return if (i < 0) t(s) else s.substring(0, i) + "  " + t(s.substring(i + 2)) }
}
