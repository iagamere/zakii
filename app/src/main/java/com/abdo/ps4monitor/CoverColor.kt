package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** The dominant colour of a game's cover (icon0.png of the PKG), used to tint cards and notifications. No extra library. */
object CoverColor {
    private val cache = ConcurrentHashMap<String, Int>()

    /** ARGB colour, or null while the cover is not available yet. Disk access: call off the main thread (or use [load]). */
    fun forDownload(d: Download): Int? {
        if (!d.iconReady) return null
        cache[d.id]?.let { return it }
        val bmp = PkgStore.bitmap("d:${d.id}", "icon0.png", 48) ?: return null
        val c = dominant(bmp)
        cache[d.id] = c
        return c
    }
    suspend fun load(d: Download): Int? = withContext(Dispatchers.IO) { forDownload(d) }
    fun forget(id: String) { cache.remove(id) }

    /** Picks the most colourful hue of the picture, then keeps it readable (not too pale, not too dark). */
    fun dominant(bmp: Bitmap): Int {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val bins = 12
        val wsum = DoubleArray(bins); val r = DoubleArray(bins); val g = DoubleArray(bins); val b = DoubleArray(bins)
        var ar = 0.0; var ag = 0.0; var ab = 0.0; var an = 0
        val hsv = FloatArray(3)
        for (p in px) {
            if (Color.alpha(p) < 128) continue
            ar += Color.red(p); ag += Color.green(p); ab += Color.blue(p); an++
            Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.2f || hsv[1] < 0.25f) continue          // ignore near-black and grey pixels
            val wgt = (hsv[1] * hsv[2]).toDouble()
            val bin = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            wsum[bin] += wgt; r[bin] += Color.red(p) * wgt; g[bin] += Color.green(p) * wgt; b[bin] += Color.blue(p) * wgt
        }
        var best = -1
        for (i in 0 until bins) if (best < 0 || wsum[i] > wsum[best]) best = i
        val base = when {
            best >= 0 && wsum[best] > 0.0 -> Color.rgb((r[best] / wsum[best]).toInt(), (g[best] / wsum[best]).toInt(), (b[best] / wsum[best]).toInt())
            an > 0 -> Color.rgb((ar / an).toInt(), (ag / an).toInt(), (ab / an).toInt())
            else -> 0xFF3F51B5.toInt()
        }
        Color.colorToHSV(base, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.9f)
        hsv[2] = hsv[2].coerceIn(0.40f, 0.70f)
        return Color.HSVToColor(hsv)
    }
}
