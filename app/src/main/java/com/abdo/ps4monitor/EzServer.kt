package com.abdo.ps4monitor
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * One download known to ezRemote Server. `path` is the FINAL file path; the data is written to "<path>.tmp".
 * Fields after `ts` exist only with the custom server (/v2 API): [id] is its handle for pause/resume/delete.
 */
class SrvEntry(val path: String, val bytes: Long, val size: Long, val state: Int, val ts: Long,
               val id: Int = 0, val speed: Double = 0.0, val conns: Int = 0, val attempts: Int = 0, val error: String = "", val v2: Boolean = false,
               val fatal: Boolean = false) {
    val pending get() = state == 0
    val active get() = state == 1 || state == 2 || state == 7   // DOWNLOADING, RESUMED, STOPPING (v2)
    val failed get() = state == 3
    val done get() = state == 4
    val paused get() = state == 5                               // v2 only
    val needsLink get() = state == 6                            // v2 only: expired link, send it again to the same file
}

/**
 * ezRemote Server (ps4-ezremote-server): the component that really downloads. Listens on 0.0.0.0:6701. Two flavours:
 *  - the ORIGINAL: GET /get_download_state -> [{path, bytes_transfered, file_size, state, timestamp}] (state 0..4), GET /stop kills the whole
 *    process. One download at a time, no per-download cancel/pause/remove (those need stop + edit of bg_download_history.json).
 *  - the CUSTOM build (ps4-ezremote-server-abdo): the same legacy routes plus /v2: GET /v2/info, GET /v2/downloads, POST /v2/pause,
 *    /v2/resume, /v2/delete {id,...}. Detected with GET /v2/info (an original server answers 404), so both keep working.
 * `file_size` of the original is what ezRemote Client measured (garbage when that failed), so it is range-checked.
 */
object EzServer {
    const val PORT = 6701
    class Reply(val code: Int, val body: String)
    private class V2Info(val ok: Boolean, val at: Long, val maxAttempts: Int)
    private val v2cache = ConcurrentHashMap<String, V2Info>()

    private fun call(ps4: Ps4, method: String, path: String, body: String?, timeoutMs: Int): Reply {
        val c = URL("http://${ps4.host}:$PORT$path").openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.requestMethod = method
        try {
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = c.responseCode
            val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
            return Reply(code, text)
        } finally { c.disconnect() }
    }
    private fun get(ps4: Ps4, path: String, timeoutMs: Int): String {
        val r = call(ps4, "GET", path, null, timeoutMs)
        if (r.code !in 200..299) throw EzError("ezRemote Server answered HTTP ${r.code}.")
        return r.body
    }

    /** True when the server is the custom build with the /v2 API. Cached; throws (like [list]) when the server is not reachable. */
    fun isV2(ps4: Ps4, timeoutMs: Int): Boolean {
        val now = System.currentTimeMillis(); val c = v2cache[ps4.id]
        if (c != null && now - c.at < (if (c.ok) 120_000L else 30_000L)) return c.ok
        val r = call(ps4, "GET", "/v2/info", null, timeoutMs)
        val info = if (r.code == 200) runCatching { JSONObject(r.body) }.getOrNull() else null
        val ok = info != null && info.optInt("api") >= 2
        val maxAtt = info?.optJSONObject("settings")?.optInt("max_attempts", 5) ?: 5
        v2cache[ps4.id] = V2Info(ok, now, maxAtt)
        return ok
    }
    /** What the last check found; never touches the network. */
    fun isV2Known(ps4: Ps4): Boolean = v2cache[ps4.id]?.ok == true
    fun maxAttempts(ps4: Ps4): Int = v2cache[ps4.id]?.maxAttempts ?: 5

    fun list(ps4: Ps4, timeoutMs: Int): List<SrvEntry> {
        if (isV2(ps4, timeoutMs)) {
            val r = call(ps4, "GET", "/v2/downloads", null, timeoutMs)
            if (r.code == 404) v2cache.remove(ps4.id)                       // replaced by an original server: use the old API below
            else if (r.code in 200..299) return parseV2(r.body)
            else throw EzError("ezRemote Server answered HTTP ${r.code}.")
        }
        return parseLegacy(get(ps4, "/get_download_state", timeoutMs))
    }
    private fun parseLegacy(text: String): List<SrvEntry> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            SrvEntry(o.optString("path"), o.optLong("bytes_transfered"), o.optLong("file_size"), o.optInt("state", -1), o.optLong("timestamp")) }
    }
    private fun parseV2(text: String): List<SrvEntry> {
        val a = JSONObject(text).optJSONArray("downloads") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            SrvEntry(o.optString("path"), o.optLong("bytes"), o.optLong("size"), o.optInt("state", -1), o.optLong("timestamp"),
                id = o.optInt("id"), speed = o.optDouble("speed", 0.0), conns = o.optInt("connections"), attempts = o.optInt("attempts"),
                error = o.optString("error"), v2 = true, fatal = o.optBoolean("final")) }
    }
    fun up(ps4: Ps4): Boolean = try { list(ps4, 3000); true } catch (e: Exception) { false }

    /** Terminates ezRemote Server (original: the only way to cancel anything). True when it no longer answers afterwards. */
    fun stop(ps4: Ps4): Boolean {
        try { get(ps4, "/stop", 3000) } catch (e: Exception) { }
        Thread.sleep(2000)
        return !up(ps4)
    }
    fun newest(l: List<SrvEntry>, path: String): SrvEntry? = l.filter { it.path == path }.maxByOrNull { it.ts }
    fun sane(size: Long) = size in (1L shl 16)..(1L shl 42)

    // ---------------- status / settings / log of the custom server ----------------
    class ServerInfo(val version: String, val custom: Boolean, val maxParallel: Int, val maxConns: Int, val auto: Boolean, val fixedConns: Int)
    /** null = not reachable. An original server answers 404 to /v2/info. */
    fun info(ps4: Ps4, timeoutMs: Int = 3000): ServerInfo? = try {
        val r = call(ps4, "GET", "/v2/info", null, timeoutMs)
        val j = if (r.code == 200) runCatching { JSONObject(r.body) }.getOrNull() else null
        if (j != null && j.optInt("api") >= 2) {
            val st = j.optJSONObject("settings")
            ServerInfo(j.optString("version"), true, st?.optInt("max_parallel", 2) ?: 2, st?.optInt("max_conns_per_download", 8) ?: 8,
                st?.optBoolean("auto_conns", true) ?: true, st?.optInt("fixed_conns", 4) ?: 4)
        } else if (r.code == 404) ServerInfo("original", false, 1, 1, false, 1)
        else null
    } catch (e: Exception) { null }
    /** Forget what was detected (after the server was replaced). */
    fun forget(ps4: Ps4) { v2cache.remove(ps4.id) }
    fun updateSettings(ps4: Ps4, json: String): Ctl = control(ps4, "/v2/settings", json)
    /** Last lines of the server's log (custom server only). */
    fun log(ps4: Ps4, lines: Int): String = get(ps4, "/v2/log?lines=$lines", 5000)
    /** The server's log FILE on the PS4 (readable even when the server is not running), last 12 KB. */
    fun logFile(ps4: Ps4): String {
        val path = "/data/ezremote-client/dlserver.log"
        val size = EzRemote.fileSize(ps4, path, 6000)
        if (size <= 0) throw EzError("dlserver.log was not found on the PS4.")
        val len = minOf(size, 12_288L).toInt()
        return String(EzRemote.readRange(ps4, path, size - len, len, 8000), Charsets.UTF_8)
    }

    // ---------------- /v2 control (custom server only) ----------------
    /** [legacy] = the server has no /v2 API (an original server): the caller falls back to the old stop-and-edit method. */
    class Ctl(val ok: Boolean, val message: String, val legacy: Boolean = false)
    private fun control(ps4: Ps4, path: String, body: String): Ctl = try {
        val r = call(ps4, "POST", path, body, 8000)
        val j = runCatching { JSONObject(r.body) }.getOrNull()
        when {
            r.code in 200..299 && j?.optBoolean("ok", true) != false -> Ctl(true, "")
            j == null && r.code == 404 -> Ctl(false, "", legacy = true)
            else -> Ctl(false, j?.optString("error").orEmpty().ifBlank { "HTTP ${r.code}" })
        }
    } catch (e: Exception) { Ctl(false, EzRemote.friendly(e)) }
    fun pause(ps4: Ps4, id: Int): Ctl = control(ps4, "/v2/pause", "{\"id\":$id}")
    fun resume(ps4: Ps4, id: Int): Ctl = control(ps4, "/v2/resume", "{\"id\":$id}")
    /** Cancels a running download without touching the others. deleteFiles: also the partial file (and the finished file of a finished one). */
    fun delete(ps4: Ps4, id: Int, deleteFiles: Boolean): Ctl = control(ps4, "/v2/delete", "{\"id\":$id,\"delete_files\":$deleteFiles}")
}

/** One element of /data/ezremote-client/bg_download_history.json (credentials are never read). */
class BgEntry(val dest: String, val size: Long, val bytes: Long, val state: Int, val attempts: Int, val ts: Long,
              val custom: Boolean = false, val pausedFlag: Boolean = false) {
    /** Original server: FAILED with failed_attempts >= 5 is never retried. The custom server retries failures by itself and marks real stops explicitly. */
    val stopped get() = if (custom) pausedFlag else state == 3 && attempts >= 5
}

/**
 * bg_download_history.json is the persistent list of ezRemote Server. It is read ONCE when the server starts; the running server uses its
 * memory copy and rewrites the file only on some events, so edits only take effect after the server is stopped and started again.
 * At start-up an entry resumes when state==1 or (state==3 and failed_attempts<5), continuing from the .tmp size.
 * Edits here are text-level on the target element only (64-bit ids stay byte-identical), validated and read back.
 */
object BgHistory {
    const val PATH = "/data/ezremote-client/bg_download_history.json"
    private val cache = ConcurrentHashMap<String, Pair<Long, List<BgEntry>>>()
    class Edit(val ok: Boolean, val message: String)

    private fun readText(p: Ps4): String {
        val size = EzRemote.fileSize(p, PATH, 8000)
        if (size <= 0) throw EzError("bg_download_history.json was not found on the PS4.")
        if (size > (2L shl 20)) throw EzError("bg_download_history.json is unexpectedly large.")
        val t = String(EzRemote.readRange(p, PATH, 0, size.toInt(), 10_000), Charsets.UTF_8)
        val end = t.lastIndexOf(']')
        if (end < 0) throw EzError("bg_download_history.json is not a JSON list.")
        return t.substring(0, end + 1)
    }
    private fun parse(t: String): List<BgEntry> {
        val a = JSONArray(t)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            BgEntry(o.optString("dest_path"), o.optLong("file_size"), o.optLong("bytes_transfered"), o.optInt("state", -1), o.optInt("failed_attempts"), o.optLong("timestamp"),
                custom = o.has("uid"), pausedFlag = o.optBoolean("paused") || o.optBoolean("needs_link") || o.optBoolean("final")) }
    }
    fun read(p: Ps4): List<BgEntry> = parse(readText(p))
    /** At most one read every 15 s per PS4; null when the file cannot be read. */
    fun cached(p: Ps4): List<BgEntry>? {
        val c = cache[p.id]; val now = System.currentTimeMillis()
        if (c != null && now - c.first < 15_000) return c.second
        val l = try { read(p) } catch (e: Exception) { null }
        if (l != null) cache[p.id] = now to l
        return l ?: c?.second
    }
    fun entry(l: List<BgEntry>?, dest: String): BgEntry? = l?.filter { it.dest == dest }?.maxByOrNull { it.ts }

    /** [start, end) of every top-level object of the JSON array (string-aware). */
    private fun spans(t: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>(); var depth = 0; var inStr = false; var esc = false; var start = -1
        for (i in t.indices) {
            val c = t[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> { depth++; if (depth == 2) start = i }
                '}' -> { if (depth == 2 && start >= 0) { out += start to (i + 1); start = -1 }; depth-- }
                '[' -> depth++
                ']' -> depth--
            }
        }
        return out
    }
    private fun destOf(raw: String, s: Pair<Int, Int>): Pair<String, Long>? = try { JSONObject(raw.substring(s.first, s.second)).let { it.optString("dest_path") to it.optLong("timestamp") } } catch (e: Exception) { null }

    private fun write(p: Ps4, out: String, expectEntries: Int): Edit {
        val check = try { parse(out) } catch (e: Exception) { return Edit(false, "The edited file would not be valid JSON; nothing was written.") }
        if (check.size != expectEntries) return Edit(false, "The edit would change the number of entries unexpectedly; nothing was written.")
        val r = EzRemote.edit(p, PATH, out)
        cache.remove(p.id)
        return if (r.ok) Edit(true, "") else Edit(false, r.message)
    }

    /** state=3 and failed_attempts=[attempts] on the newest entry for [dest]. Only call while ezRemote Server is NOT running. */
    fun mark(p: Ps4, dest: String, attempts: Int): Edit = try {
        val raw = readText(p); val spans = spans(raw)
        var best = -1; var bestTs = Long.MIN_VALUE
        spans.forEachIndexed { i, s -> val d = destOf(raw, s); if (d != null && d.first == dest && d.second >= bestTs) { best = i; bestTs = d.second } }
        if (best < 0) Edit(false, "This download is not listed in bg_download_history.json.") else {
            val (a, b) = spans[best]
            val reA = Regex("(\"failed_attempts\"\\s*:\\s*)-?\\d+"); val reS = Regex("(\"state\"\\s*:\\s*)-?\\d+")
            var el = raw.substring(a, b)
            el = if (reA.containsMatchIn(el)) el.replace(reA) { it.groupValues[1] + attempts } else el.trimEnd().removeSuffix("}") + ",\"failed_attempts\":$attempts}"
            el = if (reS.containsMatchIn(el)) el.replace(reS) { it.groupValues[1] + "3" } else el.trimEnd().removeSuffix("}") + ",\"state\":3}"
            val w = write(p, raw.substring(0, a) + el + raw.substring(b), spans.size)
            if (!w.ok) w else {
                val back = entry(read(p), dest)
                if (back != null && back.attempts == attempts && back.state == 3) w else Edit(false, "The PS4 file did not keep the change.")
            }
        }
    } catch (e: Exception) { Edit(false, EzRemote.friendly(e)) }

    /** Removes every entry for [dest] (the server forgets the download at its next start). ok=true also when it was not listed. */
    fun remove(p: Ps4, dest: String): Edit = try {
        var raw = readText(p); val total = spans(raw).size; var removed = 0
        while (removed < 50) {
            val sp = spans(raw); val idx = sp.indexOfLast { destOf(raw, it)?.first == dest }
            if (idx < 0) break
            val (a, b) = sp[idx]; var s = a; var e = b
            var i = a - 1; while (i >= 0 && raw[i].isWhitespace()) i--
            if (i >= 0 && raw[i] == ',') s = i
            else { var j = b; while (j < raw.length && raw[j].isWhitespace()) j++; if (j < raw.length && raw[j] == ',') e = j + 1 }
            raw = raw.substring(0, s) + raw.substring(e); removed++
        }
        if (removed == 0) Edit(true, "") else {
            val w = write(p, raw, total - removed)
            if (!w.ok) w else if (entry(read(p), dest) == null) w else Edit(false, "The PS4 file still lists this download.")
        }
    } catch (e: Exception) { Edit(false, EzRemote.friendly(e)) }
}
