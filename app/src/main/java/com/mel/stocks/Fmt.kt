package com.mel.stocks

import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Number formatting shared by the widget and the full-screen list. */
object Fmt {
    /**
     * Accounting style: negatives in parentheses, positives unsigned with an invisible ")" so the % signs line up.
     * One decimal under 100%, none above, to fit the column.
     */
    fun pct(v: Double): CharSequence {
        val a = abs(v)
        val num = String.format(Locale.US, if (a < 99.95) "%.1f%%" else "%.0f%%", a)
        if (v < 0 && num.any { it in '1'..'9' }) return "($num)"
        return SpannableString("$num)").apply {
            setSpan(ForegroundColorSpan(Color.TRANSPARENT), num.length, num.length + 1, 0)
        }
    }

    fun price(p: Double): String {
        val digits = when {
            abs(p) >= 1.0 -> 2
            abs(p) >= 0.01 -> 4
            else -> 6
        }
        return NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(p)
    }
}
