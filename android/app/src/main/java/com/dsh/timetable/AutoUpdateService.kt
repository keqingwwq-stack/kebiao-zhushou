package com.dsh.timetable

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * 后台自动更新课表：不打开任何界面，开一个不可见的 WebView 把教务系统课表扫下来，
 * 只在通知栏留一条静默进度。扫完自动「替换全部」，关掉自己。
 */
class AutoUpdateService : Service() {

    private var web: WebView? = null
    private var totalWeeks = 19
    private var working = false
    private var jumps = 0
    private var expired = false

    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (working) return START_NOT_STICKY
        working = true
        val url = intent?.getStringExtra("url") ?: ""
        val site = intent?.getStringExtra("site") ?: ""
        main.post { begin(url, site) }
        return START_NOT_STICKY
    }

    private fun begin(url: String, site: String) {
        try {
            Notifier.notifyUpdating(this, "正在打开教务系统…", 0, 0, false)
            val w = WebView(this)
            val s = w.settings
            s.javaScriptEnabled = true
            s.domStorageEnabled = true
            s.databaseEnabled = true
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            try {
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
            } catch (e: Throwable) {
            }
            w.addJavascriptInterface(Bridge(), "Dsh")
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    onLoaded(u ?: "")
                }
            }
            web = w
            w.loadUrl(if (url.isNotEmpty()) url else "https://ngjw.nyist.edu.cn/admin/caslogin")
        } catch (e: Throwable) {
            finish()
        }
    }

    private fun onLoaded(u: String) {
        if (expired) return
        val low = u.lowercase()
        val login = low.contains("cas/login") || low.contains("/login") || low.contains("sso") ||
            low.contains("authservice")
        if (login) {
            expired = true
            Notifier.clearUpdating(this)
            Notifier.notifyLoginExpired(this, u, "")
            finish()
            return
        }
        if (jumps < 2 && looksPortal(low)) {
            jumps++
            val target = knownJw(low)
            if (target != null) {
                Notifier.notifyUpdating(this, "正在跳转教务系统…", 0, 0, false)
                web?.loadUrl(target)
                return
            }
            findJwLink()
            return
        }
        startScan()
    }

    private fun looksPortal(low: String): Boolean {
        if (low.contains("ngjw") || low.contains("/admin/") || low.contains("jwxt")) return false
        if (low.contains("urp") || low.contains("zhjw")) return false
        return low.contains("edu.cn")
    }

    private fun knownJw(low: String): String? {
        if (low.contains("nyist.edu.cn")) return "https://ngjw.nyist.edu.cn/admin/caslogin"
        return null
    }

    private fun findJwLink() {
        val js = "(function(){var kw=['教务系统','综合教务','教学管理','教务管理','强智','URP','选课'];" +
            "var as=document.querySelectorAll('a');" +
            "for(var i=0;i<as.length;i++){var a=as[i];var t=(a.innerText||'')+' '+(a.title||'');" +
            "for(var k=0;k<kw.length;k++){if(t.indexOf(kw[k])>=0){var h=a.href||'';" +
            "if(h&&h.indexOf('javascript')<0&&h.indexOf('#')!==0){return h;}}}}return '';})()"
        try {
            web?.evaluateJavascript(js) { r ->
                val href = (r ?: "").trim().trim('"')
                if (href.startsWith("http")) {
                    Notifier.notifyUpdating(this, "正在跳转教务系统…", 0, 0, false)
                    web?.loadUrl(href)
                } else {
                    startScan()
                }
            }
        } catch (e: Throwable) {
            startScan()
        }
    }

    private fun startScan() {
        try {
            totalWeeks = try {
                Store.load(this).totalWeeks
            } catch (e: Throwable) {
                19
            }
            Notifier.notifyUpdating(this, "正在读取周次…", 0, 0, false)
            val js = assets.open("parse.js").bufferedReader().use { it.readText() }
            web?.evaluateJavascript(js) {
                web?.evaluateJavascript(
                    "(function(){try{return window.__dshScanAllWeeks(" + totalWeeks +
                        ").then(function(r){try{Dsh.weeks(JSON.stringify(r))}catch(e){}});}catch(e){try{Dsh.weeks('')}catch(e2){}}})()",
                    null
                )
            }
        } catch (e: Throwable) {
            finish()
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun progress(w: Int, tw: Int) {
            main.post {
                try {
                    Notifier.notifyUpdating(this@AutoUpdateService, "正在读取第 " + w + " / " + tw + " 周…", w, tw, false)
                } catch (e: Throwable) {
                }
            }
        }

        @JavascriptInterface
        fun weeks(json: String) {
            main.post { apply(json) }
        }
    }

    private fun apply(raw: String) {
        try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("courses")
            if (arr == null || arr.length() == 0) {
                Notifier.clearUpdating(this)
                finish()
                return
            }
            val list = ArrayList<Course>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isEmpty()) continue
                val st = Math.max(1, o.optInt("start", 1))
                val en = Math.max(st, o.optInt("end", st))
                list.add(
                    Course(
                        name = name,
                        room = o.optString("room").trim(),
                        teacher = o.optString("teacher").trim(),
                        day = Math.max(0, Math.min(6, o.optInt("day", 0))),
                        start = st,
                        end = en,
                        weeks = o.optString("weeks").ifEmpty { "1-" + totalWeeks },
                        color = o.optInt("color", i % 8)
                    )
                )
            }
            val t = Store.load(this)
            t.courses = list.toMutableList()
            val ts = obj.optString("termStart", "")
            if (ts.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) t.termStart = ts
            val tw = obj.optInt("totalWeeks", 0)
            if (tw > 0) t.totalWeeks = tw
            val ps = obj.optJSONArray("periods")
            if (ps != null) {
                for (i in 0 until ps.length()) {
                    val p = ps.optJSONObject(i) ?: continue
                    val no = p.optInt("no", 0)
                    val st = p.optString("start")
                    val en = p.optString("end")
                    if (no < 1 || !st.matches(Regex("\\d{2}:\\d{2}")) || !en.matches(Regex("\\d{2}:\\d{2}"))) continue
                    while (t.periods.size < no) t.periods.add(Period(t.periods.size + 1, "", ""))
                    t.periods[no - 1] = Period(no, st, en)
                }
            }
            Store.save(this, t)
            try {
                Store.markSynced(this, Store.activeId(this))
            } catch (e: Throwable) {
            }
            try {
                val dir = getExternalFilesDir(null)
                if (dir != null) {
                    java.io.File(dir, "timetable.json").writeText(JsonCodec.toJson(t), Charsets.UTF_8)
                }
            } catch (e: Throwable) {
            }
            try {
                com.dsh.timetable.widget.TodayWidgetProvider.refreshAll(this)
            } catch (e: Throwable) {
            }
            Notifier.notifyUpdating(this, "已更新 " + list.size + " 门课程", 1, 0, true)
            main.postDelayed({ Notifier.clearUpdating(this) }, 4000)
        } catch (e: Throwable) {
            Notifier.clearUpdating(this)
        } finally {
            finish()
        }
    }

    private fun finish() {
        main.postDelayed({
            try {
                web?.stopLoading()
                web?.destroy()
            } catch (e: Throwable) {
            }
            web = null
            working = false
            stopSelf()
        }, 800)
    }

    companion object {
        fun start(ctx: Context, url: String, site: String = "") {
            try {
                val i = Intent(ctx, AutoUpdateService::class.java)
                i.putExtra("url", url)
                i.putExtra("site", site)
                ctx.startService(i)
            } catch (e: Throwable) {
            }
        }
    }
}
