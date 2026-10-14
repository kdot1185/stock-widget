package com.mel.stocks

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.text.Editable
import android.text.TextWatcher
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/** Settings: tickers, the data source (preset or pasted-in API), and refresh timing. */
class MainActivity : Activity() {

    private lateinit var col: LinearLayout
    private var tickerLines = mutableListOf<String>()
    private lateinit var tickerBox: LinearLayout
    private lateinit var addField: EditText
    private lateinit var showNames: Switch
    private lateinit var preset: Spinner
    private lateinit var apiKey: EditText
    private lateinit var url: EditText
    private lateinit var headers: EditText
    private lateinit var pricePath: EditText
    private lateinit var prevPath: EditText
    private lateinit var pctPath: EditText
    private lateinit var namePath: EditText
    private lateinit var timePath: EditText
    private lateinit var regStartPath: EditText
    private lateinit var regEndPath: EditText
    private lateinit var tapUrl: EditText
    private lateinit var interval: EditText
    private lateinit var morning: EditText
    private lateinit var output: TextView
    private lateinit var colorPick: Spinner
    private lateinit var colorHex: EditText
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityLabel: TextView
    private lateinit var textMode: Spinner
    private lateinit var root: LinearLayout
    private lateinit var genericBox: LinearLayout
    private lateinit var etradeBox: LinearLayout
    private lateinit var etKey: EditText
    private lateinit var etSecret: EditText
    private lateinit var etCode: EditText
    private lateinit var etStatus: TextView

    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            build()
            ready = true
        } catch (t: Throwable) {
            // Show the failure instead of dying, so it can be screenshotted.
            Diag.record(this, "settings screen", t)
            setContentView(ScrollView(this).apply {
                fitsSystemWindows = true
                addView(TextView(this@MainActivity).apply {
                    setPadding(dp(16), dp(48), dp(16), dp(16))
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setTextIsSelectable(true)
                    text = "Settings failed to open:\n\n" + (Diag.last(this@MainActivity) ?: t.toString())
                })
            })
        }
    }

    private fun build() {
        val cfg = Config.load(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(40))
        }
        col = root
        val scroll = ScrollView(this).apply { addView(root) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)

        Diag.last(this)?.let { trace ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
            box.addView(TextView(this).apply {
                text = "Last error (long-press to copy):"
                textSize = 13f
                setTextColor(0xFFE0281F.toInt())
            })
            box.addView(TextView(this).apply {
                text = trace
                typeface = Typeface.MONOSPACE
                textSize = 10f
                maxLines = 14
                setTextIsSelectable(true)
            })
            box.addView(Button(this).apply {
                text = "Clear error"
                setOnClickListener { Diag.clear(this@MainActivity); root.removeView(box); StockWidget.render(this@MainActivity) }
            })
            root.addView(box)
        }

        section("Tickers")
        tickerLines = cfg.tickersText.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        tickerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0x0F000000, 14)
        }
        col.addView(tickerBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        drawTickers()

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        addField = EditText(this).apply {
            hint = "Add ticker, e.g. LRCX (or several: LRCX, AMAT)"
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> addTickers(); true }
        }
        addRow.addView(addField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(Button(this).apply { text = "Add"; setOnClickListener { addTickers() } })
        col.addView(addRow)

        showNames = Switch(this).apply {
            text = "Show company names (two-line rows)"
            isChecked = cfg.showNames
            setPadding(0, dp(8), 0, 0)
        }
        showNames.setOnCheckedChangeListener { _, _ -> preview() }
        col.addView(showNames)

        section("Appearance")
        hint("Changes show on the widget as you make them.")
        label("Card color")
        colorPick = Spinner(this).apply {
            adapter = choices(Look.SWATCH_NAMES)
            val idx = Look.SWATCHES.indexOfFirst { (it.second and 0xFFFFFF) == (cfg.bgColor and 0xFFFFFF) }
            setSelection(if (idx >= 0) idx else Look.SWATCH_NAMES.size - 1, false)
        }
        col.addView(colorPick)
        hint("Or any hex color:")
        colorHex = field(Look.hex(cfg.bgColor), mono = true)
        opacityLabel = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(10), 0, 0)
            text = "Opacity: ${cfg.opacity}%"
        }
        col.addView(opacityLabel)
        opacitySeek = SeekBar(this).apply { max = 100; progress = cfg.opacity }
        col.addView(opacitySeek)
        label("Text color")
        textMode = Spinner(this).apply {
            adapter = choices(Look.TEXT_MODES)
            setSelection(Look.TEXT_MODES.indexOf(cfg.textMode).coerceAtLeast(0), false)
        }
        col.addView(textMode)

        section("Data source")
        hint("Pick a preset, or paste any JSON quote API. {symbol} and {key} are filled in per ticker.")
        preset = Spinner(this).apply {
            adapter = choices(Presets.NAMES)
            val idx = Presets.NAMES.indexOf(cfg.preset)
            setSelection(if (idx >= 0) idx else Presets.NAMES.size - 1, false)
        }
        col.addView(preset)

        // E*TRADE: OAuth keys and a daily sign-in instead of URL + paths.
        etradeBox = box()
        col = etradeBox
        hint("Keys from developer.etrade.com (production). E*TRADE ends sessions at midnight ET, so sign in once a day. Futures like GC=F and Yahoo-style symbols won't resolve.")
        val et = Etrade.load(this)
        label("Consumer key")
        etKey = field(et.key, mono = true)
        label("Consumer secret")
        etSecret = field(et.secret, mono = true)
        etStatus = TextView(this).apply { textSize = 13f; setPadding(0, dp(12), 0, dp(4)) }
        col.addView(etStatus)
        col.addView(Button(this).apply { text = "1. Sign in to E*TRADE"; setOnClickListener { etradeStart() } })
        label("Verification code from E*TRADE")
        etCode = field("", mono = true)
        col.addView(Button(this).apply { text = "2. Finish sign-in"; setOnClickListener { etradeFinish() } })
        col = root

        genericBox = box()
        col = genericBox
        label("API key")
        apiKey = field(cfg.apiKey, mono = true)
        label("Request URL")
        url = field(cfg.url, lines = 3, mono = true)
        label("Headers (optional)")
        hint("One per line, e.g. \"X-API-KEY: {key}\".")
        headers = field(cfg.headers, lines = 2, mono = true)

        section("Where the numbers are")
        hint("Paths into the JSON: a.b.0.c, [\"key with spaces\"], .last = last non-null in a list, \"a | b\" = fallback. Use Test to see the raw response.")
        label("Price")
        pricePath = field(cfg.pricePath, mono = true)
        label("Previous close (used if no % change path)")
        prevPath = field(cfg.prevPath, mono = true)
        label("% change (optional)")
        pctPath = field(cfg.pctPath, mono = true)
        label("Name (optional)")
        namePath = field(cfg.namePath, mono = true)
        label("Pre / after-hours tag (optional)")
        hint("Quote time, regular-session start and end. Shows PRE or AH next to the price.")
        timePath = field(cfg.timePath, mono = true)
        regStartPath = field(cfg.regStartPath, mono = true)
        regEndPath = field(cfg.regEndPath, mono = true)
        col = root

        section("Refresh")
        label("Background refresh every N minutes (15 minimum, 0 = off)")
        interval = field(cfg.intervalMin.toString(), numeric = true)
        label("Morning refresh at (HH:MM, 24-hour; blank = off)")
        morning = field(cfg.morning)
        label("Open on tap (optional)")
        tapUrl = field(cfg.tapUrl, mono = true)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        buttons.addView(Button(this).apply { text = "Save"; setOnClickListener { save() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply { text = "Test first ticker"; setOnClickListener { test() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        col.addView(buttons)

        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(output)

        colorPick.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Look.SWATCHES.getOrNull(position)?.let { colorHex.setText(Look.hex(it.second)) }
                preview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        colorHex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val c = Look.parseHex(e.toString()) ?: return
                val idx = Look.SWATCHES.indexOfFirst { (it.second and 0xFFFFFF) == (c and 0xFFFFFF) }
                val want = if (idx >= 0) idx else Look.SWATCH_NAMES.size - 1
                if (colorPick.selectedItemPosition != want) colorPick.setSelection(want, false)
                preview()
            }
        })
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { opacityLabel.text = "Opacity: $p%" }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) = preview()
        })
        textMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = preview()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        preset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Presets.byName(Presets.NAMES[position])?.let(::applyPreset)
                showSourceFields()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Leaving the screen saves too: edits shouldn't depend on finding the button at the bottom. */
    override fun onPause() {
        super.onPause()
        if (!ready) return
        try { autosave() } catch (t: Throwable) { Diag.record(this, "autosave", t) }
    }

    private fun autosave() {
        val c = current()
        if (c.morning.isNotEmpty() && Config.parseTime(c.morning) == null) return
        val changed = c != Config.load(this)
        Etrade.saveKeys(this, etKey.text.toString(), etSecret.text.toString())
        if (!changed) return
        Config.save(this, c)
        try { Scheduler.ensure(this) } catch (t: Throwable) { Diag.record(this, "schedule", t) }
        sendBroadcast(Intent(this, StockWidget::class.java).setAction(StockWidget.ACTION_REFRESH))
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        etStatus.text = Etrade.status(this)
        showSourceFields()
    }

    /** Persist and redraw the widget immediately so appearance changes can be judged in place. */
    private fun preview() {
        if (!ready) return
        try {
            Config.save(this, current())
            StockWidget.render(this)
        } catch (t: Throwable) {
            Diag.record(this, "preview", t)
        }
    }

    private fun isEtrade() = Presets.NAMES[preset.selectedItemPosition] == Etrade.PRESET

    private fun showSourceFields() {
        etradeBox.visibility = if (isEtrade()) View.VISIBLE else View.GONE
        genericBox.visibility = if (isEtrade()) View.GONE else View.VISIBLE
    }

    private fun etradeStart() {
        Etrade.saveKeys(this, etKey.text.toString(), etSecret.text.toString())
        etStatus.text = "Contacting E*TRADE…"
        thread {
            try {
                val link = Etrade.startSignIn(this)
                runOnUiThread {
                    etStatus.text = "Approve access in the browser, copy the code it shows, paste it below, then tap Finish. The code is good for 5 minutes."
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
                }
            } catch (e: Exception) {
                runOnUiThread { etStatus.text = "Sign-in failed: ${e.message}" }
            }
        }
    }

    private fun etradeFinish() {
        val code = etCode.text.toString().trim()
        if (code.isEmpty()) { etStatus.text = "Paste the verification code first."; return }
        etStatus.text = "Finishing sign-in…"
        thread {
            try {
                Etrade.finishSignIn(this, code)
                runOnUiThread {
                    etCode.setText("")
                    etStatus.text = Etrade.status(this)
                    save()
                }
            } catch (e: Exception) {
                runOnUiThread { etStatus.text = "Couldn't finish sign-in: ${e.message}" }
            }
        }
    }

    private fun applyPreset(p: Preset) {
        url.setText(p.url)
        headers.setText(p.headers)
        pricePath.setText(p.pricePath)
        prevPath.setText(p.prevPath)
        pctPath.setText(p.pctPath)
        namePath.setText(p.namePath)
        timePath.setText(p.timePath)
        regStartPath.setText(p.regStartPath)
        regEndPath.setText(p.regEndPath)
    }

    private fun current(): Config {
        val chosen = Presets.NAMES[preset.selectedItemPosition]
        val p = Presets.byName(chosen)
        // Editing a preset's fields by hand turns it into Custom, so the spinner never lies.
        val edited = p != null && (p.url != url.text.toString().trim() || p.pricePath != pricePath.text.toString().trim())
        return Config(
            tickersText = tickerLines.joinToString("\n"),
            showNames = showNames.isChecked,
            preset = if (edited) Presets.CUSTOM else chosen,
            url = url.text.toString().trim(),
            apiKey = apiKey.text.toString().trim(),
            headers = headers.text.toString(),
            pricePath = pricePath.text.toString().trim(),
            prevPath = prevPath.text.toString().trim(),
            pctPath = pctPath.text.toString().trim(),
            namePath = namePath.text.toString().trim(),
            timePath = timePath.text.toString().trim(),
            regStartPath = regStartPath.text.toString().trim(),
            regEndPath = regEndPath.text.toString().trim(),
            tapUrl = tapUrl.text.toString().trim(),
            intervalMin = interval.text.toString().trim().toIntOrNull()?.let { if (it <= 0) 0 else maxOf(15, it) } ?: 30,
            morning = morning.text.toString().trim(),
            bgColor = Look.parseHex(colorHex.text.toString()) ?: Config.load(this).bgColor,
            opacity = opacitySeek.progress,
            textMode = Look.TEXT_MODES[textMode.selectedItemPosition.coerceAtLeast(0)],
        )
    }

    private fun save() {
        val c = current()
        if (c.morning.isNotEmpty() && Config.parseTime(c.morning) == null) {
            Toast.makeText(this, "Morning time must look like 06:55", Toast.LENGTH_SHORT).show()
            return
        }
        Config.save(this, c)
        Etrade.saveKeys(this, etKey.text.toString(), etSecret.text.toString())
        if (c.preset == Presets.CUSTOM) preset.setSelection(Presets.NAMES.size - 1, false)
        interval.setText(c.intervalMin.toString())
        try { Scheduler.ensure(this) } catch (t: Throwable) { Diag.record(this, "schedule", t) }
        sendBroadcast(Intent(this, StockWidget::class.java).setAction(StockWidget.ACTION_REFRESH))
        Toast.makeText(this, if (StockWidget.hasWidgets(this)) "Saved — refreshing" else "Saved. Add the widget from your home screen.", Toast.LENGTH_SHORT).show()
    }

    private fun test() {
        val c = current()
        val sym = c.tickers.firstOrNull()?.symbol
        if (sym == null) { output.text = "Add a ticker first."; return }
        if (c.preset == Etrade.PRESET) {
            Etrade.saveKeys(this, etKey.text.toString(), etSecret.text.toString())
            output.text = "Fetching from E*TRADE…"
            thread {
                val text = try {
                    val rs = Etrade.fetchAll(this, c.tickers.map { it.symbol }.distinct())
                    rs.joinToString("\n") { r ->
                        r.symbol + "  " + (r.error ?: "${r.price}  ${r.pct?.let { String.format(Locale.US, "%+.2f%%", it) } ?: ""}  ${r.session ?: ""}  ${r.name ?: ""}")
                    } + "\n\nRaw response:\n" + pretty(rs.firstOrNull { it.raw != null }?.raw)
                } catch (e: Exception) { "ERROR: ${e.message}" }
                runOnUiThread { output.text = text }
            }
            return
        }
        output.text = "Fetching $sym…\n${c.requestUrl(sym)}"
        thread {
            val r = Quotes.fetchOne(c, sym)
            val summary = buildString {
                appendLine(c.requestUrl(sym))
                appendLine()
                if (r.error != null) appendLine("ERROR: ${r.error}")
                appendLine("price   ${r.price ?: "—"}")
                appendLine("prev    ${r.prev ?: "—"}")
                appendLine("change  ${r.pct?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "—"}")
                appendLine("name    ${r.name ?: "—"}")
                appendLine("session ${r.session ?: "regular / unknown"}")
                appendLine()
                appendLine("Raw response:")
                append(pretty(r.raw))
            }
            runOnUiThread { output.text = summary }
        }
    }

    private fun pretty(raw: String?): String {
        if (raw == null) return "(none)"
        val s = try {
            val t = raw.trim()
            if (t.startsWith("[")) JSONArray(t).toString(2) else JSONObject(t).toString(2)
        } catch (e: Exception) { raw }
        return if (s.length > 6000) s.take(6000) + "\n… (truncated)" else s
    }

    // --- tiny view helpers ---

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private val ink: Int by lazy {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val c = a.getColor(0, android.graphics.Color.BLACK)
        a.recycle()
        c
    }

    private fun rounded(color: Int, radiusDp: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** Spinner whose closed state actually shows the selection, in the theme's text color. */
    private fun choices(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(ink)
                textSize = 16f
                setPadding(dp(4), dp(10), dp(4), dp(10))
            }

        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                setTextColor(ink)
                textSize = 16f
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
    }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    // --- ticker list editor ---

    private fun drawTickers() {
        tickerBox.removeAllViews()
        if (tickerLines.isEmpty()) {
            tickerBox.addView(TextView(this).apply {
                text = "No tickers yet"
                alpha = 0.6f
                textSize = 15f
                setPadding(dp(14), dp(14), dp(14), dp(14))
            })
            return
        }
        for ((i, line) in tickerLines.withIndex()) {
            val parts = line.split(Regex("\\s+"), limit = 2)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(4), 0)
                minimumHeight = dp(46)
            }
            row.addView(TextView(this).apply {
                text = parts[0].uppercase()
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ink)
            })
            row.addView(TextView(this).apply {
                text = parts.getOrNull(1) ?: ""
                textSize = 13f
                alpha = 0.6f
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fun icon(label: String, enabled: Boolean, desc: String, onTap: () -> Unit) = TextView(this).apply {
                text = label
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                contentDescription = desc
                setTextColor(ink)
                alpha = if (enabled) 0.75f else 0.15f
                isEnabled = enabled
                setOnClickListener { onTap() }
            }.also { row.addView(it, LinearLayout.LayoutParams(dp(42), dp(42))) }
            icon("↑", i > 0, "Move up") { move(i, -1) }
            icon("↓", i < tickerLines.size - 1, "Move down") { move(i, 1) }
            icon("✕", true, "Remove") { tickerLines.removeAt(i); tickersChanged(refresh = false) }
            if (i > 0) tickerBox.addView(android.widget.FrameLayout(this).apply {
                setBackgroundColor(0x14000000)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(14) })
            tickerBox.addView(row)
        }
    }

    private fun move(i: Int, by: Int) {
        val j = i + by
        if (j !in tickerLines.indices) return
        tickerLines[i] = tickerLines[j].also { tickerLines[j] = tickerLines[i] }
        tickersChanged(refresh = false)
    }

    private fun addTickers() {
        val have = tickerLines.map { it.split(Regex("\\s+"))[0].uppercase() }.toMutableSet()
        val new = addField.text.toString().split(Regex("[,;\\s]+")).map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && have.add(it) }
        addField.setText("")
        if (new.isEmpty()) return
        tickerLines.addAll(new)
        tickersChanged(refresh = true)
    }

    /** Ticker edits save immediately; adding fetches quotes for the newcomers. */
    private fun tickersChanged(refresh: Boolean) {
        drawTickers()
        if (!ready) return
        try {
            Config.save(this, current())
            if (refresh) sendBroadcast(Intent(this, StockWidget::class.java).setAction(StockWidget.ACTION_REFRESH))
            else StockWidget.render(this)
        } catch (t: Throwable) {
            Diag.record(this, "tickers", t)
        }
    }

    private fun box(): LinearLayout {
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(b)
        return b
    }

    private fun section(text: String) {
        col.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(24), 0, dp(2))
        })
    }

    private fun label(text: String) {
        col.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun hint(text: String) {
        col.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            alpha = 0.65f
            setPadding(0, dp(2), 0, dp(2))
        })
    }

    private fun field(value: String, lines: Int = 1, mono: Boolean = false, numeric: Boolean = false): EditText {
        val e = EditText(this).apply {
            setText(value)
            textSize = 14f
            if (mono) typeface = Typeface.MONOSPACE
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER
                lines > 1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (lines > 1) { minLines = lines; isSingleLine = false } else isSingleLine = true
        }
        col.addView(e)
        return e
    }
}
