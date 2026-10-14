package com.mel.stocks

import android.app.Application
import android.appwidget.AppWidgetManager
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config as RConfig

@RunWith(RobolectricTestRunner::class)
@RConfig(sdk = [35])
class SmokeTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun settingsOpenEditAndLeave() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = c.get()
        val add = MainActivity::class.java.getDeclaredField("addField").apply { isAccessible = true }
        val lines = MainActivity::class.java.getDeclaredField("tickerLines").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") (lines.get(a) as MutableList<String>).clear()
        (add.get(a) as EditText).setText("aapl, msft")
        MainActivity::class.java.getDeclaredMethod("addTickers").apply { isAccessible = true }.invoke(a)
        c.pause().stop().destroy()
        println("DIAG settings: " + Diag.last(app))
        assertEquals("AAPL\nMSFT", Config.load(app).tickersText)
    }

    @Test
    fun widgetRendersWithoutError() {
        val shadow = Shadows.shadowOf(AppWidgetManager.getInstance(app))
        shadow.createWidget(StockWidget::class.java, R.layout.widget)
        StockWidget.render(app)
        println("DIAG widget: " + Diag.last(app))
        assertNull(Diag.last(app))
    }

    @Test
    fun widgetRendersDarkTranslucent() {
        Config.save(app, Config.load(app).copy(bgColor = 0xFF14213D.toInt(), opacity = 40, textMode = "Auto"))
        val shadow = Shadows.shadowOf(AppWidgetManager.getInstance(app))
        shadow.createWidget(StockWidget::class.java, R.layout.widget)
        StockWidget.render(app)
        println("DIAG dark: " + Diag.last(app) + " ink=" + Look.ink(Config.load(app)))
        assertNull(Diag.last(app))
    }

    @Test
    fun historyLive() {
        val err = History.refresh(app, listOf("MU", "SNDK", "META"))
        println("HISTORY err=$err")
        for (s in listOf("MU", "SNDK", "META")) {
            val c = History.load(app, s)
            val last = c?.days?.lastOrNull()
            println("HISTORY $s n=${c?.days?.size} last=$last changes(vs last close)=${History.changes(app, s, last?.second)}")
        }
    }

    @Test
    fun sortCycles() {
        val shadow = Shadows.shadowOf(AppWidgetManager.getInstance(app))
        shadow.createWidget(StockWidget::class.java, R.layout.widget)
        val snap = Store.Snapshot(
            mapOf(
                "A" to Store.Row("A", null, 10.0, -2.0, null, false),
                "B" to Store.Row("B", null, 10.0, 3.0, null, false),
                "C" to Store.Row("C", null, 10.0, null, null, false),
            ), 1L, 1L, 0, null,
        )
        val t = listOf(Ticker("A", null), Ticker("B", null), Ticker("C", null))
        Sort.tap(app, "1D"); val desc = Sort.apply(app, t, snap).map { it.symbol }
        Sort.tap(app, "1D"); val asc = Sort.apply(app, t, snap).map { it.symbol }
        Sort.tap(app, "1D"); val off = Sort.apply(app, t, snap).map { it.symbol }
        println("SORT desc=$desc asc=$asc off=$off")
        assertEquals(listOf("B", "A", "C"), desc)
        assertEquals(listOf("A", "B", "C"), asc)
        assertEquals(listOf("A", "B", "C"), off)
        app.sendBroadcast(android.content.Intent(app, StockWidget::class.java).setAction(StockWidget.ACTION_SORT_5D))
        StockWidget.render(app)
        assertNull(Diag.last(app))
    }

    @Test
    fun refreshEndToEnd() {
        val shadow = Shadows.shadowOf(AppWidgetManager.getInstance(app))
        shadow.createWidget(StockWidget::class.java, R.layout.widget)
        StockWidget.refresh(app)
        val s = Store.load(app)
        println("SNAPSHOT updatedAt=${s.updatedAt} failed=${s.failed} note=${s.note} rows=${s.rows}")
        println("DIAG refresh: " + Diag.last(app))
        assertNull(Diag.last(app))
    }

}
