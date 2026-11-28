package com.parchipos.app

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/**
 * The portal's faces for text set from code (layouts get them from the theme):
 * Fraunces for the shop's name and big titles, Instrument Sans for the rest.
 * Android 7 has no font resources, so the system faces stand in there.
 */
object Fonts {
    fun display(c: Context): Typeface =
        if (Build.VERSION.SDK_INT >= 26) c.resources.getFont(R.font.fraunces_semibold) else Typeface.create(Typeface.SERIF, Typeface.BOLD)

    fun sans(c: Context, bold: Boolean = false): Typeface {
        val family = if (Build.VERSION.SDK_INT >= 26) c.resources.getFont(R.font.instrument_sans) else Typeface.SANS_SERIF
        return Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
}
