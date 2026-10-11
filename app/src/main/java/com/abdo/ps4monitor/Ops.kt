package com.abdo.ps4monitor
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/**
 * Operations ezRemote performs synchronously on the PS4 (delete, copy, move, extract, install, rename, new folder, pause/resume/delete of
 * downloads). They run in an app-level scope so they outlive the screen, keep the foreground service alive and report with a toast.
 * One at a time: ezRemote refuses most of these while another activity is in progress. Outcomes ezRemote does not report are verified by listing.
 */
object Ops {
    class Item(val path: String, val isDir: Boolean)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val active = MutableStateFlow(0)
    val label = MutableStateFlow("")
    val refresh = MutableStateFlow(0)          // bumped when an operation ends: file lists reload
    @Volatile private var current: Job? = null
    fun cancel() { current?.cancel() }

    private fun toast(m: String) { Handler(Looper.getMainLooper()).post { Toast.makeText(DownloadMonitor.app, m.take(500), Toast.LENGTH_LONG).show() } }

    fun launch(text: String, work: suspend () -> String): Boolean {
        if (active.value > 0) { toast(tr("Another operation is still running.", "عملية أخرى ما زالت جارية.")); return false }
        active.value = 1; label.value = text
        DownloadMonitor.startSvc()
        current = scope.launch {
            val msg = try { work() }
                catch (e: CancellationException) { tr("Cancelled.", "أُلغيت العملية.") }
                catch (e: Exception) { Tx.t(PkgInspector.friendly(e)) }
            DownloadMonitor.d("Operation finished: $text -> ${msg.take(120)}")
            toast(msg)
            active.value = 0; label.value = ""; refresh.update { it + 1 }
        }
        return true
    }

    private fun depth(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }.size
    private fun parent(path: String) = path.substringBeforeLast('/', "/").ifEmpty { "/" }
    private fun names(p: Ps4, dir: String): Set<String> = PkgInspector.browse(p, dir).map { it.name }.toSet()
    private fun fail(r: EzRemote.OpResult) = Tx.t(r.message)

    /** True when [path] is not inside the PS4's download folder: probably not a file the user downloaded. */
    fun outside(p: Ps4, path: String): Boolean { val base = DownloadMonitor.norm(p.dest); return !(path == base || path.startsWith("$base/")) }

    /** ezRemote remove (recursive) + verification by listing the parent folders. */
    suspend fun deleteItems(p: Ps4, items: List<Item>): String = withContext(Dispatchers.IO) {
        val paths = items.map { it.path }.distinct()
        if (paths.any { depth(it) < 2 }) return@withContext tr("Refusing to delete a top-level system folder.", "رفض حذف مجلد نظام رئيسي.")
        val r = EzRemote.remove(p, paths)
        val left = try { paths.groupBy { parent(it) }.flatMap { (dir, ps) -> val have = names(p, dir); ps.filter { it.substringAfterLast('/') in have } } } catch (e: Exception) { null }
        when {
            left == null -> if (r.ok) tr("Deleted ${paths.size} item(s) from the PS4.", "حُذف ${paths.size} عنصر من الـPS4.") else fail(r)
            left.isEmpty() -> tr("Deleted ${paths.size} item(s) from the PS4.", "حُذف ${paths.size} عنصر من الـPS4.")
            else -> tr("Not deleted: ", "لم يُحذف: ") + left.joinToString { it.substringAfterLast('/') } + (if (r.ok) "" else "\n" + fail(r))
        }
    }

    suspend fun install(p: Ps4, paths: List<String>): String = withContext(Dispatchers.IO) {
        val r = EzRemote.install(p, paths)
        if (r.ok) tr("ezRemote accepted the install request. The PS4 shows the install progress itself; this app cannot track it.",
                     "قبل ezRemote طلب التثبيت. الـPS4 يعرض تقدم التثبيت بنفسه؛ التطبيق لا يستطيع تتبّعه.") else fail(r)
    }

    suspend fun mkdir(p: Ps4, path: String): String = withContext(Dispatchers.IO) {
        val r = EzRemote.createFolder(p, path)
        if (!r.ok) return@withContext fail(r)
        if (path.substringAfterLast('/') in names(p, parent(path))) tr("Folder created.", "أُنشئ المجلد.") else tr("The PS4 did not create the folder.", "لم ينشئ الـPS4 المجلد.")
    }

    suspend fun renameItem(p: Ps4, from: String, newName: String): String = withContext(Dispatchers.IO) {
        if (newName.isBlank() || '/' in newName) return@withContext tr("Invalid name.", "اسم غير صالح.")
        if (depth(from) < 2) return@withContext tr("Refusing to rename a top-level system folder.", "رفض إعادة تسمية مجلد نظام رئيسي.")
        val dir = parent(from)
        if (newName in names(p, dir)) return@withContext tr("An item named $newName already exists.", "يوجد عنصر باسم $newName بالفعل.")
        EzRemote.rename(p, from, joinPath(dir, newName))
        val after = names(p, dir)
        if (newName in after && from.substringAfterLast('/') !in after) tr("Renamed to $newName.", "أُعيدت التسمية إلى $newName.") else tr("The PS4 did not rename it.", "لم يُعد الـPS4 تسميته.")
    }

    suspend fun copyMove(p: Ps4, items: List<Pair<String, FsEntry>>, destDir: String, move: Boolean): String = withContext(Dispatchers.IO) {
        val paths = items.map { joinPath(it.first, it.second.name) }
        if (move && paths.any { depth(it) < 2 }) return@withContext tr("Refusing to move a top-level system folder.", "رفض نقل مجلد نظام رئيسي.")
        for ((dir, e) in items) {
            val src = joinPath(dir, e.name)
            if (e.isDir && (destDir == src || destDir.startsWith("$src/"))) return@withContext tr("A folder cannot be copied or moved into itself.", "لا يمكن نسخ أو نقل مجلد إلى داخله.")
            if (dir == destDir) return@withContext tr("The destination is the same folder.", "الوجهة هي نفس المجلد.")
        }
        val r = if (move) EzRemote.move(p, paths, destDir) else EzRemote.copy(p, paths, destDir)
        val have = try { names(p, destDir) } catch (e: Exception) { emptySet() }
        val missing = items.filter { it.second.name !in have }
        when {
            missing.isEmpty() && r.ok -> tr(if (move) "Moved ${items.size} item(s)." else "Copied ${items.size} item(s).", if (move) "نُقل ${items.size} عنصر." else "نُسخ ${items.size} عنصر.")
            missing.isEmpty() -> tr("Done, but ezRemote reported: ", "تم، لكن ezRemote أبلغ: ") + fail(r)
            else -> tr("Not everything arrived: ${missing.joinToString { it.second.name }}. ", "لم يصل كل شيء: ${missing.joinToString { it.second.name }}. ") + (if (r.ok) "" else fail(r))
        }
    }

    suspend fun extract(p: Ps4, archive: String): String = withContext(Dispatchers.IO) {
        val dir = parent(archive); val name = archive.substringAfterLast('/'); val folder = name.substringBeforeLast('.', name).ifBlank { name + "_extracted" }
        val r = EzRemote.extract(p, archive, dir, folder)
        if (!r.ok) return@withContext fail(r)
        if (folder in names(p, dir)) tr("Extracted into the folder $folder.", "فُكّ الضغط في المجلد $folder.") else tr("ezRemote reported success but the folder was not found.", "أبلغ ezRemote بالنجاح لكن لم يوجد المجلد.")
    }
}
