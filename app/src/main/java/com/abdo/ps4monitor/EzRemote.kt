package com.abdo.ps4monitor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.*
import java.text.SimpleDateFormat
import java.util.Locale

class EzError(msg: String) : IOException(msg)

/**
 * ezRemote Client web server (default port 8080). Every request shape below was read from its http_server.cpp.
 * ezRemote ignores the return value of several operations (rename, createFolder) and always answers success: callers verify by listing.
 */
object EzRemote {
    sealed class Submit {
        object Accepted : Submit()
        data class Rejected(val message: String) : Submit()
        data class Unreachable(val message: String) : Submit()
    }
    class OpResult(val ok: Boolean, val message: String)

    private fun url(ps4: Ps4, path: String) = "http://${ps4.host}:${ps4.httpPort}$path"
    private fun post(ps4: Ps4, path: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val c = URL(url(ps4, path)).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = connectMs; c.readTimeout = readMs; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        return c
    }
    private fun readBody(c: HttpURLConnection): String =
        runCatching { (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")

    fun friendly(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "The PS4 did not answer in time."
        is ConnectException, is NoRouteToHostException -> "Cannot reach the PS4 web server. Is ezRemote running with its web server enabled?"
        is UnknownHostException -> "PS4 address not found."
        is EzError -> e.message ?: "ezRemote error"
        else -> "PS4 web connection problem."
    }

    /** POST /__local__/download_url {url, dest, use_alldebrid, use_realdebrid}. [dest] must be the FINAL FILE PATH. 200 only means "queued". */
    fun submit(ps4: Ps4, url: String, destPath: String): Submit {
        val body = JSONObject().put("url", url).put("dest", destPath).put("use_alldebrid", false).put("use_realdebrid", false).toString()
        val c = try { post(ps4, "/__local__/download_url", 10_000, 60_000) } catch (e: Exception) { return Submit.Unreachable(friendly(e)) }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode; val text = readBody(c)
            DownloadMonitor.d("ezRemote response: HTTP $code ${text.take(200)}")
            if (code !in 200..299) return Submit.Rejected("ezRemote answered HTTP $code.")
            val r = runCatching { JSONObject(text).optJSONObject("result") }.getOrNull()
            if (r != null && !r.optBoolean("success", false)) {
                val err = r.optString("error").takeIf { it.isNotBlank() && it != "null" }
                if (err == "Failed to download") return Submit.Rejected("ezRemote could not hand the download to ezRemote Server (port 6701). The background server may not be running: restart ezRemote Client on the PS4.")
                return Submit.Rejected("ezRemote rejected the request" + (err?.let { ": $it" } ?: "."))
            }
            return Submit.Accepted
        } catch (e: Exception) {
            DownloadMonitor.d("ezRemote submit error: ${e.javaClass.simpleName}: ${e.message}")
            return Submit.Unreachable(friendly(e))
        } finally { c.disconnect() }
    }

    /** POST /__local__/list {path, onlyFolders} -> {"result":[{name,rights,date,size(string),type:"dir"|"file"}]} */
    fun list(ps4: Ps4, path: String, timeoutMs: Int): List<FsEntry> {
        val c = post(ps4, "/__local__/list", timeoutMs, timeoutMs)
        try {
            c.outputStream.use { it.write(JSONObject().put("path", path).put("onlyFolders", false).toString().toByteArray()) }
            val code = c.responseCode; val text = readBody(c)
            if (code !in 200..299) throw EzError("ezRemote list answered HTTP $code")
            val o = JSONObject(text)
            val arr = o.optJSONArray("result") ?: throw EzError("Unrecognised list reply")
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { f ->
                val n = f.optString("name"); if (n.isEmpty() || n == "." || n == "..") return@mapNotNull null
                FsEntry(n, f.optString("size").toLongOrNull() ?: 0L, parseDate(f.optString("date")), f.optString("type") == "dir")
            }
        } finally { c.disconnect() }
    }
    /** "date" is "YYYY-MM-DD HH:MM:SS" (PS4 wall clock), parsed with the phone zone so it is displayed unchanged. */
    private fun parseDate(t: String): Long = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(t)?.time ?: 0L }.getOrDefault(0L)

    private fun op(ps4: Ps4, path: String, body: JSONObject, connectMs: Int, readMs: Int): OpResult {
        val c = post(ps4, path, connectMs, readMs)
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode; val text = readBody(c)
            if (code !in 200..299) return OpResult(false, "ezRemote answered HTTP $code.")
            val r = runCatching { JSONObject(text).optJSONObject("result") }.getOrNull()
            return if (r != null && r.optBoolean("success", false)) OpResult(true, "")
                   else OpResult(false, r?.optString("error")?.takeIf { it.isNotBlank() && it != "null" } ?: "ezRemote refused the request.")
        } finally { c.disconnect() }
    }
    private fun items(l: List<String>) = JSONArray(l)

    /** POST /__local__/remove {"items":[...]} — recursive (folders go with their content); refused while ezRemote is busy with another activity. */
    fun remove(ps4: Ps4, paths: List<String>, readMs: Int = 120_000) = op(ps4, "/__local__/remove", JSONObject().put("items", items(paths)), 10_000, readMs)
    fun rename(ps4: Ps4, from: String, to: String) = op(ps4, "/__local__/rename", JSONObject().put("item", from).put("newItemPath", to), 10_000, 30_000)
    fun createFolder(ps4: Ps4, path: String) = op(ps4, "/__local__/createFolder", JSONObject().put("newPath", path), 10_000, 30_000)
    /** POST /__local__/move | copy {"items":[..],"newPath":destinationFolder} — synchronous, can take very long for big files. */
    fun move(ps4: Ps4, paths: List<String>, dest: String) = op(ps4, "/__local__/move", JSONObject().put("items", items(paths)).put("newPath", dest), 10_000, 3_600_000)
    fun copy(ps4: Ps4, paths: List<String>, dest: String) = op(ps4, "/__local__/copy", JSONObject().put("items", items(paths)).put("newPath", dest), 10_000, 3_600_000)
    /** POST /__local__/extract {"item","destination","folderName"} (zip/rar/7z). */
    fun extract(ps4: Ps4, item: String, destination: String, folderName: String) =
        op(ps4, "/__local__/extract", JSONObject().put("item", item).put("destination", destination).put("folderName", folderName), 10_000, 3_600_000)
    /** POST /__local__/install {"items":[paths]} — the PS4 shows the install progress itself. */
    fun install(ps4: Ps4, paths: List<String>) = op(ps4, "/__local__/install", JSONObject().put("items", items(paths)), 10_000, 300_000)
    /** POST /__local__/edit {"item","content"} — overwrites a file (used only for bg_download_history.json). */
    fun edit(ps4: Ps4, path: String, content: String) = op(ps4, "/__local__/edit", JSONObject().put("item", path).put("content", content), 10_000, 30_000)

    /** GET /__local__/uploadResumeSize?destination=&filename= -> {"size":N}; 0 when the file does not exist. A cheap single-file size query. */
    fun fileSize(ps4: Ps4, path: String, timeoutMs: Int): Long {
        val dir = path.substringBeforeLast('/', "/").ifEmpty { "/" }; val name = path.substringAfterLast('/')
        val c = URL(url(ps4, "/__local__/uploadResumeSize?destination=" + URLEncoder.encode(dir, "UTF-8") + "&filename=" + URLEncoder.encode(name, "UTF-8"))).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        try { if (c.responseCode !in 200..299) throw EzError("ezRemote answered HTTP ${c.responseCode}."); return JSONObject(readBody(c)).optLong("size", 0L) } finally { c.disconnect() }
    }

    /**
     * GET /__local__/downloadFile?path=... with a Range header: reads [length] bytes at [offset] and drops the connection.
     * If this build ignores Range (answers 200) the first bytes are read, or skipped up to 64 MB. Only call it for paths known to exist:
     * the handler opens the file without checking.
     */
    fun readRange(ps4: Ps4, path: String, offset: Long, length: Int, timeoutMs: Int): ByteArray {
        val c = URL(url(ps4, "/__local__/downloadFile?path=" + URLEncoder.encode(path, "UTF-8"))).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
        try {
            val code = c.responseCode
            if (code != 206 && code != 200) throw EzError("ezRemote file read answered HTTP $code")
            val ins = c.inputStream
            var skip = if (code == 200 && offset > 0) offset else 0L
            if (skip > (64L shl 20)) throw EzError("ezRemote ignored the range request and the offset is too large")
            val tmp = ByteArray(65536)
            while (skip > 0) { val r = ins.read(tmp, 0, minOf(skip, tmp.size.toLong()).toInt()); if (r < 0) break; skip -= r }
            val buf = ByteArray(length); var n = 0
            while (n < length) { val r = ins.read(buf, n, length - n); if (r < 0) break; n += r }
            return buf.copyOf(n)
        } finally { c.disconnect() }
    }
}
