package com.quran.labs.feature.autoquran.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.graphics.createBitmap
import com.quran.data.model.audio.Qari
import com.quran.mobile.di.qualifier.ApplicationContext
import dev.zacsweers.metro.Inject
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Generates deterministic "album art" for a [Qari] for Android Auto surfaces.
 *
 * We prefer bytes (PNG) via Media3's MediaMetadata.setArtworkData so Android Auto can render
 * without any URI resolution.
 */
class QariArtworkProvider @Inject constructor(
  @param:ApplicationContext private val appContext: Context,
) {
  private val lock = Any()
  private var titleTypeface: Typeface? = null

  // Cache by qari id. Small cap because the list of qaris is bounded.
  private val cache = LruCache<Int, ByteArray>(32)

  // Cache by (qariId * 1000 + sura). At most ~228 entries (2 qaris * 114 suras).
  private val suraCache = LruCache<Int, ByteArray>(256)

  fun artworkPngFor(qari: Qari): ByteArray? {
    synchronized(lock) {
      cache.get(qari.id)?.let { return it }
    }

    val name = runCatching { appContext.getString(qari.nameResource) }.getOrNull().orEmpty()
    val png = generatePng(qari.id, name)

    if (png != null) {
      synchronized(lock) {
        cache.put(qari.id, png)
      }
    }
    return png
  }

  fun suraArtworkPngFor(qari: Qari, sura: Int): ByteArray? {
    val key = qari.id * 1000 + sura
    synchronized(lock) {
      suraCache.get(key)?.let { return it }
    }

    val png = generateSuraPng(qari.id, sura)
    if (png != null) {
      synchronized(lock) {
        suraCache.put(key, png)
      }
    }
    return png
  }

  private fun generatePng(qariId: Int, displayName: String): ByteArray? {
    val sizePx = 512

    return try {
      val bitmap = createBitmap(sizePx, sizePx)
      val canvas = Canvas(bitmap)

      val (bgColor, textColor) = colorsFor(qariId)
      val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
      }
      canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), bgPaint)

      val initials = initialsFor(displayName).ifEmpty { "Q" }
      val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = if (initials.length <= 1) sizePx * 0.48f else sizePx * 0.38f
      }

      // Center baseline using font metrics.
      val fm = textPaint.fontMetrics
      val x = sizePx / 2f
      val y = sizePx / 2f - (fm.ascent + fm.descent) / 2f
      canvas.drawText(initials, x, y, textPaint)

      ByteArrayOutputStream().use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
      }
    } catch (_: OutOfMemoryError) {
      null
    }
  }

  private fun generateSuraPng(qariId: Int, sura: Int): ByteArray? {
    val sizePx = 512

    return try {
      val bitmap = createBitmap(sizePx, sizePx)
      val canvas = Canvas(bitmap)

      val (bgColor, textColor) = colorsFor(qariId)
      val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bgColor
      }
      canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), bgPaint)

      val glyph = suraGlyph(sura)
      val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        typeface = suraTitleTypeface()
        textSize = sizePx * 0.20f
      }

      val fm = glyphPaint.fontMetrics
      val x = sizePx / 2f
      val y = sizePx / 2f - (fm.ascent + fm.descent) / 2f
      canvas.drawText(glyph, x, y, glyphPaint)

      ByteArrayOutputStream().use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
      }
    } catch (_: OutOfMemoryError) {
      null
    }
  }

  private fun suraGlyph(sura: Int): String {
    val i = sura - 1
    val codePoint = 0xFB8D + i + if (i >= 37) 0x21 else 0
    return String(Character.toChars(codePoint))
  }

  private fun suraTitleTypeface(): Typeface {
    return titleTypeface ?: run {
      val tf = Typeface.createFromAsset(appContext.assets, "quran_titles.ttf")
      titleTypeface = tf
      tf
    }
  }

  private fun colorsFor(qariId: Int): Pair<Int, Int> {
    val hue = ((qariId * 37) % 360).toFloat()
    val bg = Color.HSVToColor(floatArrayOf(hue, 0.20f, 0.95f))
    val text = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.35f))
    return bg to text
  }

  private fun initialsFor(name: String): String {
    val cleaned = name.trim()
    if (cleaned.isEmpty()) return ""

    val parts = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
    val a = firstLetterOrDigit(parts.getOrNull(0)).orEmpty()
    val b = firstLetterOrDigit(parts.getOrNull(1)).orEmpty()
    val raw = (a + b).ifEmpty {
      // Try from the full string.
      firstLetterOrDigit(cleaned).orEmpty()
    }

    val locale = Locale.getDefault()
    val upper = raw.uppercase(locale)
    return upper.substring(0, min(2, max(1, upper.length)))
  }

  private fun firstLetterOrDigit(s: String?): String? {
    val str = s?.trim().orEmpty()
    val ch = str.firstOrNull { it.isLetterOrDigit() } ?: return null
    return ch.toString()
  }
}

