package com.mel.stocks

import android.content.Context

/**
 * Layout of the full-screen list: which columns, in what order, the title and width of the notes column.
 * Kept apart from [Config] so the settings screen's save of everything else never resets it.
 */
object Columns {
    const val NOTES = "NOTES"
    const val PRICE = "PRICE"
    const val DEFAULT_NOTES_TITLE = "Notes"

    /** Every movable column (Symbol is pinned on the left and isn't one of them). */
    val ALL: List<String> = listOf(NOTES, PRICE) + History.Span.values().map { it.label }

    private const val PREFS = "columns"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved order; columns missing from it (e.g. added in a later version) go at the end. */
    fun order(ctx: Context): List<String> {
        val saved = p(ctx).getString("order", null)?.split(',')?.map { it.trim() }.orEmpty()
        val known = saved.filter { it in ALL }.distinct()
        return known + ALL.filter { it !in known }
    }

    fun setOrder(ctx: Context, keys: List<String>) = p(ctx).edit().putString("order", keys.joinToString(",")).apply()

    fun title(ctx: Context, key: String): String = when (key) {
        NOTES -> notesTitle(ctx)
        PRICE -> "Last"
        else -> key
    }

    fun notesTitle(ctx: Context): String = p(ctx).getString("notesTitle", null)?.trim()?.ifEmpty { null } ?: DEFAULT_NOTES_TITLE

    fun setNotesTitle(ctx: Context, t: String) = p(ctx).edit().putString("notesTitle", t.trim()).apply()

    /** Notes column width in dp; 0 means fit the longest note. */
    fun notesWidthDp(ctx: Context): Int = p(ctx).getInt("notesWidth", 0)

    fun setNotesWidthDp(ctx: Context, dp: Int) = p(ctx).edit().putInt("notesWidth", dp.coerceAtLeast(0)).apply()
}

/** Your own text per ticker, shown in the notes column. */
object Notes {
    private const val PREFS = "notes"

    fun get(ctx: Context, symbol: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(symbol, "") ?: ""

    fun set(ctx: Context, symbol: String, text: String) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (text.isBlank()) e.remove(symbol) else e.putString(symbol, text.trim())
        e.apply()
    }
}
