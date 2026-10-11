package com.abdo.ps4monitor
import android.content.Context
import android.net.Uri
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sends the server payload (an .elf) to GoldHEN's BinLoader on the PS4: TCP port 9090, write the whole file, close.
 * The file is chosen once and kept inside the app, so later sending is one tap (and the monitor can re-send it by itself
 * when the server stops answering).
 */
object ServerLoader {
    const val PORT = 9090
    fun elfFile(ctx: Context) = File(ctx.filesDir, "server.elf")

    /** Copies the chosen file into the app. Returns an error message, or null when it is saved. */
    fun importElf(ctx: Context, uri: Uri): String? {
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "Could not read the file."
            if (bytes.size < 1024 || bytes.size > 64 * 1024 * 1024) return "This does not look like a PS4 payload (unexpected size)."
            val elf = bytes[0].toInt() == 0x7F && bytes[1].toInt() == 0x45 && bytes[2].toInt() == 0x4C && bytes[3].toInt() == 0x46
            if (!elf) return "This is not an ELF file."
            elfFile(ctx).writeBytes(bytes)
            return null
        } catch (e: Exception) { return EzRemote.friendly(e) }
    }

    /** Null = sent. Needs "Enable BinLoader Server" in GoldHEN's settings. */
    fun send(host: String, file: File, onProgress: (Int) -> Unit): String? {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, PORT), 5000)
                s.soTimeout = 15000
                val out = s.getOutputStream()
                val total = maxOf(1L, file.length()); var sent = 0L
                file.inputStream().use { input ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); sent += n
                        onProgress((sent * 100 / total).toInt())
                    }
                }
                out.flush()
                try { s.shutdownOutput() } catch (e: Exception) { }
            }
            return null
        } catch (e: Exception) { return EzRemote.friendly(e) }
    }
}
