package com.mel.stocks

import android.graphics.Color

/** Text and accent colors for the widget, chosen to read against the card color and opacity. */
data class Ink(
    val primary: Int,
    val muted: Int,
    val divider: Int,
    val icon: Int,
    val up: Int,
    val down: Int,
)

object Look {
    val SWATCHES: List<Pair<String, Int>> = listOf(
        "White" to 0xFFFFFFFF.toInt(),
        "Light gray" to 0xFFF2F2F7.toInt(),
        "Silver" to 0xFFC3C6CF.toInt(),
        "Graphite" to 0xFF2C2C2E.toInt(),
        "Black" to 0xFF000000.toInt(),
        "Navy" to 0xFF14213D.toInt(),
    )
    const val CUSTOM = "Custom"
    val SWATCH_NAMES = SWATCHES.map { it.first } + CUSTOM

    val TEXT_MODES = listOf("Auto", "Dark text", "Light text")

    private val DARK_TEXT = Ink(
        primary = 0xFF1D1D1F.toInt(), muted = 0xFF86868B.toInt(), divider = 0xFFE8E8ED.toInt(),
        icon = 0xFF6E6E73.toInt(), up = 0xFF2F55D4.toInt(), down = 0xFFE0281F.toInt(),
    )
    private val LIGHT_TEXT = Ink(
        primary = 0xFFF5F5F7.toInt(), muted = 0xFFA1A1A6.toInt(), divider = 0x33FFFFFF,
        icon = 0xFFC7C7CC.toInt(), up = 0xFF6F8FFF.toInt(), down = 0xFFFF6B60.toInt(),
    )

    /** Colors for an app screen on a light (false) or dark (true) background. */
    fun inkFor(darkBackground: Boolean): Ink = if (darkBackground) LIGHT_TEXT else DARK_TEXT

    fun ink(cfg: Config): Ink = when (cfg.textMode) {
        "Dark text" -> DARK_TEXT
        "Light text" -> LIGHT_TEXT
        // A mostly see-through card shows the wallpaper, so judge by the wallpaper (assumed dark) instead.
        else -> if (cfg.opacity < 45 || luminance(cfg.bgColor) < 0.45) LIGHT_TEXT else DARK_TEXT
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    /** "#1E2A3B", "1e2a3b", "#abc" -> opaque color; anything else -> null. */
    fun parseHex(s: String): Int? {
        var h = s.trim().removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length != 6 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return (0xFF000000.toInt()) or h.toInt(16)
    }
}
