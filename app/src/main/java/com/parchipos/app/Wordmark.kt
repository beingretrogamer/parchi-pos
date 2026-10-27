package com.parchipos.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * The Scoops & Sips wordmark at the top of the bill.
 *
 * Drawn rather than shipped as an image, so it is crisp at either paper width
 * and there is no logo file to lose. Settings shows the same bitmap as a
 * preview of what will print.
 */
object Wordmark {
    const val NAME = "Scoops & Sips"
    private const val TAGLINE = "ICE CREAM  ·  SHAKES  ·  SNACKS"

    private val cache = HashMap<Int, ByteArray>()

    /** Black on white, `width` dots wide: 576 for 80mm paper, 384 for 58mm. */
    fun bitmap(width: Int): Bitmap {
        // Short on purpose: the logo should brand the slip, not lengthen it.
        val h = (width * 0.19f).toInt()
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create("cursive", Typeface.BOLD)
            textSize = h * 0.62f
        }
        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = h * 0.15f
            letterSpacing = 0.12f
        }

        val iconW = h * 0.5f
        val gap = h * 0.14f
        // Shrink the name until cone + gap + text fits with a small margin.
        val room = width * 0.94f - iconW - gap
        while (title.measureText(NAME) > room && title.textSize > 8f) title.textSize *= 0.95f
        val textW = maxOf(title.measureText(NAME), tag.measureText(TAGLINE))
        val x0 = (width - (iconW + gap + textW)) / 2f

        drawCone(c, x0, iconW, h.toFloat())

        val tx = x0 + iconW + gap
        c.drawText(NAME, tx + (textW - title.measureText(NAME)) / 2f, h * 0.62f, title)
        c.drawText(TAGLINE, tx + (textW - tag.measureText(TAGLINE)) / 2f, h * 0.93f, tag)
        return bmp
    }

    /** Raster bit image (GS v 0), ready to drop into a print job. */
    @Synchronized
    fun escpos(width: Int): ByteArray = cache.getOrPut(width) {
        val bmp = bitmap(width)
        val h = bmp.height
        val rowBytes = (width + 7) / 8
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1d, 0x76, 0x30, 0, rowBytes.toByte(), (rowBytes shr 8).toByte(), h.toByte(), (h shr 8).toByte()))
        val px = IntArray(width)
        for (y in 0 until h) {
            bmp.getPixels(px, 0, width, 0, y, width, 1)
            for (bx in 0 until rowBytes) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = bx * 8 + bit
                    if (x < width) {
                        val p = px[x]
                        val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                        if (lum < 128) b = b or (0x80 shr bit)
                    }
                }
                out.write(b)
            }
        }
        out.toByteArray()
    }

    /** A cone: a solid scoop on a cross-hatched wafer, `w` wide and `h` tall from x0. */
    private fun drawCone(c: Canvas, x0: Float, w: Float, h: Float) {
        val cx = x0 + w / 2f
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        val top = h * 0.46f
        val wafer = Path().apply {
            moveTo(x0 + w * 0.06f, top)
            lineTo(x0 + w * 0.94f, top)
            lineTo(cx, h * 0.98f)
            close()
        }
        val line = Paint(ink).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(2f, h * 0.035f) }
        c.save()
        c.clipPath(wafer)
        val step = h * 0.13f
        var d = -h
        while (d < w + h) {
            c.drawLine(x0 + d, top, x0 + d + h, top + h, line)
            c.drawLine(x0 + d + h, top, x0 + d, top + h, line)
            d += step
        }
        c.restore()
        c.drawPath(wafer, line)

        // Three overlapping circles read as a scoop; the bottom edge is
        // straightened by the wafer's rim.
        val r = w * 0.34f
        c.drawCircle(cx - r * 0.55f, h * 0.38f, r * 0.85f, ink)
        c.drawCircle(cx + r * 0.55f, h * 0.38f, r * 0.85f, ink)
        c.drawCircle(cx, h * 0.24f, r, ink)
        c.drawRect(x0 + w * 0.06f, h * 0.38f, x0 + w * 0.94f, top, ink)
        // A highlight, so the blob looks round rather than flat.
        val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = maxOf(2f, h * 0.04f)
            strokeCap = Paint.Cap.ROUND
        }
        c.drawArc(cx - r * 0.6f, h * 0.24f - r * 0.6f, cx + r * 0.6f, h * 0.24f + r * 0.6f, 200f, 60f, false, shine)
    }
}
