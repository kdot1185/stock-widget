package com.mel.stocks

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.thread

/**
 * Unattended daily E*TRADE sign-in. Does what you'd do by hand: opens the authorize page in an
 * off-screen WebView, logs in with the saved username/password, presses Accept, reads the
 * verification code off the page and trades it for the day's access token.
 *
 * Credentials live only on the phone, encrypted with an Android Keystore key.
 * If E*TRADE ever bounces the saved password, auto sign-in stops until you re-save it,
 * so a stale password can't lock the account with repeated attempts.
 */
object AutoLogin {
    private const val PREFS = "etrade_auto"
    private const val KEY_ALIAS = "etrade_login"
    private const val TIMEOUT_MS = 120_000L
    private const val POLL_MS = 1_500L
    private const val MIN_GAP_MS = 30 * 60_000L

    // ---------- credentials ----------

    fun saveCreds(ctx: Context, user: String, pass: String) {
        prefs(ctx).edit()
            .putString("u", encrypt(user.trim()))
            .putString("p", encrypt(pass))
            .putBoolean("bad", false)
            .apply()
    }

    fun clearCreds(ctx: Context) = prefs(ctx).edit().remove("u").remove("p").remove("bad").apply()

    fun hasCreds(ctx: Context) = prefs(ctx).contains("u") && prefs(ctx).contains("p")

    fun savedUser(ctx: Context): String = runCatching { prefs(ctx).getString("u", null)?.let(::decrypt) }.getOrNull() ?: ""

    /** True when unattended sign-in is allowed to run right now. */
    fun enabled(ctx: Context) = hasCreds(ctx) && !prefs(ctx).getBoolean("bad", false)

    fun status(ctx: Context): String {
        val p = prefs(ctx)
        return when {
            !hasCreds(ctx) -> "Automatic sign-in is off. Save your E*TRADE login below to turn it on."
            p.getBoolean("bad", false) -> "Paused: E*TRADE rejected the saved password. Re-save your login to resume."
            else -> "On. " + (p.getString("last", null) ?: "Hasn't run yet.")
        }
    }

    // ---------- scheduling ----------

    /** Called when a refresh hits "sign-in needed": queue one sign-in job, rate-limited. */
    fun request(ctx: Context): Boolean {
        if (!enabled(ctx)) return false
        val last = prefs(ctx).getLong("tryAt", 0L)
        if (System.currentTimeMillis() - last < MIN_GAP_MS) return false
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return true
        val base = JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoLoginJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        // Expedited so it runs right away even in Doze (the morning alarm); fall back if out of quota.
        val ok = runCatching { js.schedule(base.setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        if (!ok) js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, AutoLoginJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        return true
    }

    const val JOB_ID = 8

    // ---------- the sign-in itself ----------

    private class BadPassword(msg: String) : IOException(msg)

    /** Blocking; never call on the main thread. Throws with a description of the page if anything goes wrong. */
    fun run(ctx: Context) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "AutoLogin.run on main thread" }
        val app = ctx.applicationContext
        val p = prefs(app)
        p.edit().putLong("tryAt", System.currentTimeMillis()).apply()
        val user = savedUser(app)
        val pass = runCatching { p.getString("p", null)?.let(::decrypt) }.getOrNull()
        if (user.isEmpty() || pass.isNullOrEmpty()) throw IOException("No saved E*TRADE login")

        try {
            val url = Etrade.startSignIn(app)
            val code = scrapeCode(app, url, user, pass)
            Etrade.finishSignIn(app, code)
            p.edit().putString("last", "Last signed in automatically ${stamp()}.").apply()
        } catch (e: BadPassword) {
            p.edit().putBoolean("bad", true).putString("last", "Failed ${stamp()}: ${e.message}").apply()
            throw e
        } catch (e: Exception) {
            p.edit().putString("last", "Failed ${stamp()}: ${e.message?.take(300)}").apply()
            throw e
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun scrapeCode(ctx: Context, url: String, user: String, pass: String): String {
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var code: String? = null
        var failure: Exception? = null
        var lastSeen = "page never loaded"
        var web: WebView? = null
        var submits = 0
        var accepted = false

        val script = SCRIPT
            .replace("__USER__", JSONObject.quote(user))
            .replace("__PASS__", JSONObject.quote(pass))

        val tick = object : Runnable {
            override fun run() {
                val w = web ?: return
                if (done.count == 0L) return
                w.evaluateJavascript("(function(){$script})()") { raw ->
                    if (done.count == 0L) return@evaluateJavascript
                    val res = runCatching { JSONObject(JSONObject("{\"v\":$raw}").getString("v")) }.getOrNull()
                    if (res != null) {
                        lastSeen = res.optString("page").take(240)
                        when (res.optString("state")) {
                            "code" -> { code = res.optString("code"); done.countDown(); return@evaluateJavascript }
                            "login" -> {
                                // Login form showing again after we already submitted twice = wrong password.
                                if (submits >= 2) {
                                    failure = BadPassword("E*TRADE rejected the saved password. Page: $lastSeen")
                                    done.countDown(); return@evaluateJavascript
                                }
                                if (res.optBoolean("submitted")) submits++
                            }
                            "accept" -> if (res.optBoolean("submitted")) accepted = true
                        }
                    }
                    main.postDelayed(this, POLL_MS)
                }
            }
        }

        main.post {
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                web = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Look like regular mobile Chrome rather than an embedded WebView.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = WebViewClient()
                    layout(0, 0, 1080, 2000)
                    loadUrl(url)
                }
                main.postDelayed(tick, POLL_MS * 2)
            } catch (e: Exception) {
                failure = e; done.countDown()
            }
        }

        val finished = done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        main.post { runCatching { web?.stopLoading(); web?.destroy() }; web = null }
        failure?.let { throw it }
        if (!finished) throw IOException("Timed out (${if (accepted) "after Accept" else if (submits > 0) "after login" else "before login"}). Page: $lastSeen")
        return code?.takeIf { it.isNotBlank() } ?: throw IOException("No verification code found. Page: $lastSeen")
    }

    /**
     * Runs on every poll. Figures out which E*TRADE screen is showing and does the next step:
     * login form → fill + submit; authorize screen → press Accept; code screen → return the code.
     * Returns JSON {state, submitted?, code?, page}. Each action is done at most once per page.
     */
    private val SCRIPT = """
        var U = __USER__, P = __PASS__;
        function vis(e){ var r=e.getBoundingClientRect(); var s=getComputedStyle(e); return s.display!='none' && s.visibility!='hidden' && (r.width>0||r.height>0); }
        function setVal(e,v){ var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value'); d.set.call(e,v);
            e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); e.dispatchEvent(new Event('blur',{bubbles:true})); }
        function label(e){ return ((e.innerText||'')+' '+(e.value||'')+' '+(e.getAttribute('aria-label')||'')).trim().toLowerCase(); }
        function buttons(){ return Array.prototype.slice.call(document.querySelectorAll('button,input[type=submit],input[type=button],a[role=button]')).filter(vis); }
        var page = (document.body ? document.body.innerText : '').replace(/\s+/g,' ').trim();
        var out = {state:'waiting', page: page.slice(0,240)};
        var w = window.__ol || (window.__ol = {});
        var here = location.href;

        // 1) Login form.
        var pw = Array.prototype.slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];
        if (pw) {
            out.state = 'login';
            if (w.loginAt === here) return JSON.stringify(out);
            var cands = Array.prototype.slice.call(document.querySelectorAll('input[type=text],input[type=email],input:not([type])')).filter(vis);
            var un = cands.filter(function(e){ return /user|login|logon|email|id/i.test((e.name||'')+(e.id||'')+(e.getAttribute('autocomplete')||'')); })[0] || cands[0];
            if (!un) return JSON.stringify(out);
            setVal(un, U); setVal(pw, P);
            var b = buttons().filter(function(e){ return /log ?on|log ?in|sign ?in|submit|continue/.test(label(e)); })[0];
            setTimeout(function(){ if (b) b.click(); else if (pw.form) { if (pw.form.requestSubmit) pw.form.requestSubmit(); else pw.form.submit(); } }, 400);
            w.loginAt = here; out.submitted = true;
            return JSON.stringify(out);
        }

        // 2) Authorize screen: press Accept.
        var acc = buttons().filter(function(e){ return /^\s*accept\b|\bagree\b|\ballow\b|\bauthorize\b/.test(label(e)); })[0];
        if (acc) {
            out.state = 'accept';
            if (w.acceptAt !== here) { acc.click(); w.acceptAt = here; out.submitted = true; }
            return JSON.stringify(out);
        }

        // 3) Verification code screen: a short alphanumeric code, usually in a read-only box.
        var ins = Array.prototype.slice.call(document.querySelectorAll('input')).filter(function(e){ return vis(e) && e.type!='hidden'; });
        for (var i=0;i<ins.length;i++){ var v=(ins[i].value||'').trim(); if (/^[A-Z0-9]{4,10}$/.test(v) && v.toLowerCase()!=U.toLowerCase()) { out.state='code'; out.code=v; return JSON.stringify(out);} }
        if (/verification code|enter this code|copy the code|code below/i.test(page)) {
            var m = page.match(/(?:verification code|code)[^A-Za-z0-9]{0,60}([A-Z0-9]{5,10})\b/);
            if (m) { out.state='code'; out.code=m[1]; }
        }
        return JSON.stringify(out);
    """.trimIndent()

    // ---------- storage helpers ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stamp() = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(Date())

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, ct) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}

/** Runs the unattended sign-in, then refreshes the widget. */
class AutoLoginJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "etrade-autologin") {
            try {
                Store.note(applicationContext, "Signing in to E*TRADE…")
                StockWidget.render(applicationContext)
                AutoLogin.run(applicationContext)
            } catch (t: Throwable) {
                Diag.record(applicationContext, "E*TRADE auto sign-in", t)
            }
            StockWidget.refresh(applicationContext, allowAutoLogin = false)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
