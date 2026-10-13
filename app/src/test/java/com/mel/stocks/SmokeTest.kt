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
        val f = MainActivity::class.java.getDeclaredField("tickers").apply { isAccessible = true }
        (f.get(a) as EditText).setText("AAPL\nMSFT")
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
