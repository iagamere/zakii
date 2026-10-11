package com.abdo.ps4monitor
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class Settings(val interval: Int, val timeout: Int, val stuck: Int, val notif: Boolean)
data class Bookmark(val title: String, val url: String, val icon: String = "")   // icon = base64 PNG

object Store {
    lateinit var sp: SharedPreferences
    lateinit var secure: SharedPreferences      // encrypted: PS4 profiles and download records
    val theme = mutableIntStateOf(0)            // 0 system, 1 light, 2 dark
    val dynamic = mutableStateOf(true)          // use the phone's own (Material You) colours

    fun init(c: Context) {
        sp = c.getSharedPreferences("app", 0)
        val key = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        secure = EncryptedSharedPreferences.create(c, "secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        theme.intValue = sp.getInt("theme", 0); dynamic.value = sp.getBoolean("dynamic", true); Lang.mode = sp.getInt("lang", 0)
        Ps4Repo.load(); DownloadRepo.load()
    }
    fun settings() = Settings(sp.getInt("poll", 3), sp.getInt("timeout", 10), sp.getInt("stuck", 60), sp.getBoolean("notif", true))
    fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    fun setTheme(i: Int) { theme.intValue = i; putInt("theme", i) }
    fun setLang(i: Int) { Lang.mode = i; putInt("lang", i) }
    fun setDynamic(b: Boolean) { dynamic.value = b; sp.edit().putBoolean("dynamic", b).apply() }
    fun nextNotifId(): Int { val n = sp.getInt("notifseq", 100) + 1; sp.edit().putInt("notifseq", n).apply(); return n }

    /** Paths the user deleted: never adopted again from ezRemote Server / the history file. */
    fun ignore(ps4Id: String, path: String) { val s = (sp.getStringSet("ignoredsrv", emptySet()) ?: emptySet()).toMutableSet(); s += "$ps4Id|$path"; sp.edit().putStringSet("ignoredsrv", s).apply() }
    fun ignored(ps4Id: String, path: String) = (sp.getStringSet("ignoredsrv", emptySet()) ?: emptySet()).contains("$ps4Id|$path")

    fun bookmarks(): List<Bookmark> = runCatching {
        val a = JSONArray(sp.getString("bookmarks2", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Bookmark(o.optString("n"), o.optString("u"), o.optString("i")) } }
    }.getOrDefault(emptyList())
    private fun saveBookmarks(l: List<Bookmark>) = sp.edit().putString("bookmarks2",
        JSONArray(l.map { JSONObject().put("n", it.title).put("u", it.url).put("i", it.icon) }).toString()).apply()
    fun addBookmark(b: Bookmark) = saveBookmarks(bookmarks().filter { it.url != b.url } + b)
    fun deleteBookmark(url: String) = saveBookmarks(bookmarks().filter { it.url != url })
}

object Ps4Repo {
    val list = MutableStateFlow<List<Ps4>>(emptyList())
    val activeId = MutableStateFlow<String?>(null)

    fun load() {
        val l = runCatching {
            val a = JSONArray(Store.secure.getString("ps4s", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Ps4(o.getString("id"), o.getString("name"), o.getString("host"), o.optInt("http", 8080), o.optString("dest", "/data/pkg")) } }
        }.getOrDefault(emptyList())
        list.value = l
        activeId.value = Store.sp.getString("activeps4", null)?.takeIf { id -> l.any { it.id == id } } ?: l.firstOrNull()?.id
    }
    private fun persist(l: List<Ps4>) = Store.secure.edit().putString("ps4s", JSONArray(l.map {
        JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("http", it.httpPort).put("dest", it.dest) }).toString()).apply()
    fun get(id: String?) = list.value.firstOrNull { it.id == id }
    fun active() = get(activeId.value)
    fun setActive(id: String) { activeId.value = id; Store.sp.edit().putString("activeps4", id).apply() }
    fun save(p: Ps4) {
        val l = if (list.value.any { it.id == p.id }) list.value.map { if (it.id == p.id) p else it } else list.value + p
        list.value = l; persist(l); if (activeId.value == null) setActive(p.id)
    }
    fun delete(id: String) {
        val l = list.value.filter { it.id != id }; list.value = l; persist(l)
        if (activeId.value == id) { activeId.value = l.firstOrNull()?.id; Store.sp.edit().putString("activeps4", activeId.value).apply() }
    }
}

/** Persistent download records (encrypted prefs, JSON). Survives UI / service / process death. */
object DownloadRepo {
    val all = MutableStateFlow<List<Download>>(emptyList())
    private var lastSave = 0L

    fun get(id: String) = all.value.firstOrNull { it.id == id }
    fun add(d: Download) { all.update { listOf(d) + it }; flush(true) }
    fun remove(id: String) { all.update { l -> l.filter { it.id != id } }; flush(true) }
    fun update(id: String, f: (Download) -> Download) {
        var structural = false
        all.update { l -> l.map { if (it.id == id) { val n = f(it).copy(updatedAt = System.currentTimeMillis())
            structural = n.state != it.state || n.terminalNotified != it.terminalNotified || n.pkgTitle != it.pkgTitle || n.superseded != it.superseded
            n } else it } }
        flush(structural)        // byte counters are written at most every 10 s
    }
    fun flush(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 10_000) return
        lastSave = now
        val arr = JSONArray()
        all.value.take(300).forEach { d ->
            arr.put(JSONObject().put("id", d.id).put("ps4", d.ps4Id).put("url", d.sourceUrl).put("name", d.displayName).put("dest", d.dest)
                .put("fname", d.fileName ?: JSONObject.NULL).put("att", d.attempt).put("sup", d.superseded)
                .put("exp", d.expectedSize ?: JSONObject.NULL).put("size", d.currentSize).put("speed", d.speed)
                .put("state", d.state.name).put("note", d.note).put("err", d.errorMessage ?: JSONObject.NULL)
                .put("created", d.createdAt).put("submitted", d.submittedAt).put("started", d.startedAt).put("completed", d.completedAt)
                .put("nid", d.notificationId).put("tn", d.terminalNotified)
                .put("ptitle", d.pkgTitle ?: JSONObject.NULL).put("ptid", d.titleId ?: JSONObject.NULL).put("icon", d.iconReady).put("upd", d.updatedAt))
        }
        Store.secure.edit().putString("downloads", arr.toString()).apply()
    }
    fun load() {
        val a = runCatching { JSONArray(Store.secure.getString("downloads", "[]")) }.getOrDefault(JSONArray())
        all.value = (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            fun s(k: String) = if (o.isNull(k)) null else o.getString(k)
            val fname = s("fname")
            var st = DlState.parse(o.getString("state"), fname != null)
            var err = s("err"); var note = o.optString("note")
            if (fname == null && st.active) { st = DlState.FAILED; err = "Old record: it cannot be followed by file path any more."; note = "" }
            Download(o.getString("id"), o.getString("ps4"), o.optString("url"), o.getString("name"), o.getString("dest"), fname,
                o.optInt("att", 1), o.optBoolean("sup"), if (o.isNull("exp")) null else o.getLong("exp"), o.optLong("size"), 0.0, -1,
                st, note, err, o.getLong("created"), o.optLong("submitted"), o.optLong("started"), o.optLong("completed"),
                o.getInt("nid"), o.optBoolean("tn"), s("ptitle"), s("ptid"), o.optBoolean("icon"), o.optLong("upd", o.getLong("created")))
        }.getOrNull() }
    }
}
