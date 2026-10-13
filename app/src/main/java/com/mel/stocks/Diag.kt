package com.mel.stocks

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records the last error with its stack trace so it can be read in settings instead of vanishing into logcat. */
object Diag {
    private const val PREFS = "diag"

    fun record(ctx: Context, where: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date())
        Log.e("Watchlist", where, t)
        // commit(), not apply(): the process may be about to die.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last", "$stamp · $where\n${sw.toString().take(6000)}")
            .commit()
    }

    fun last(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)

    fun clear(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("last").apply()
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try { Diag.record(this, "crash on ${th.name}", e) } catch (x: Throwable) { }
            prev?.uncaughtException(th, e)
        }
    }
}
