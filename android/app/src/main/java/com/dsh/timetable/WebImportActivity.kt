package com.dsh.timetable

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 跳转网页导入：内置浏览器打开学校教务系统，登录后进到课表页，
 * 点「识别本页课表」把当前页面的表格/文字解析成课程。
 */
class WebImportActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var etUrl: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tvWhere: TextView
    private var autoMode = false
    private var scanLayer: android.view.View? = null
    private var scanTitle: android.widget.TextView? = null
    private var scanText: android.widget.TextView? = null
    private var scanBar: android.widget.ProgressBar? = null
    private var notifiedExpired = false
    private var autoJump = 0
    private var autoScanStarted = false
    private var silentMode = false

    private val jwKeywords = arrayOf("教务系统", "综合教务", "教学管理", "教务管理", "强智", "URP", "选课")

    /** 学校官网 / 门户（不是教务系统本身）才需要先跳一步 */
    private fun looksPortal(u: String): Boolean {
        val low = u.lowercase()
        if (low.contains("ngjw") || low.contains("jw.nyist") || low.contains("/admin/")) return false
        if (low.contains("/login") || low.contains("cas/")) return false
        if (low.contains("jwxt") || low.contains("urp") || low.contains("zhjw")) return false
        return low.contains("nyist.edu.cn") || low.contains("edu.cn")
    }

    /** 已知学校：官网直接映射到教务系统入口 */
    private fun knownJw(u: String): String? {
        val low = u.lowercase()
        if (low.contains("nyist.edu.cn")) return "https://ngjw.nyist.edu.cn/admin/caslogin"
        return null
    }

    /** 通用做法：在门户页里找「教务系统」这类链接，找到就跳过去 */
    private fun findJwLink() {
        val kw = jwKeywords.joinToString("','") { it }
        val js = "(function(){var kw=['" + kw + "'];" +
            "var as=document.querySelectorAll('a');" +
            "for(var i=0;i<as.length;i++){var a=as[i];var t=(a.innerText||'')+' '+(a.title||'');" +
            "for(var k=0;k<kw.length;k++){if(t.indexOf(kw[k])>=0){var h=a.href||'';" +
            "if(h&&h.indexOf('javascript')<0&&h.indexOf('#')!==0){return h;}}}}return '';})()"
        try {
            web.evaluateJavascript(js) { r ->
                val href = (r ?: "").trim().trim('"')
                if (href.startsWith("http")) {
                    tvWhere.text = "当前页面：正在跳转教务系统…"
                    web.loadUrl(href)
                }
            }
        } catch (e: Throwable) {
        }
    }

    private val dayNames = "一二三四五六日"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        silentMode = intent?.getBooleanExtra("silent", false) ?: false
        if (silentMode) {
            setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_import)
        if (silentMode) {
            try {
                // 注意：不能把 WebView 设成 INVISIBLE —— Chromium 会认为页面不可见而节流 JS 定时器，
                // 全周扫描会卡住。只把窗口缩到 1x1，视图仍保持 VISIBLE。
                window.setLayout(1, 1)
                window.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
                web.resumeTimers()
            } catch (e: Throwable) {
            }
        }

        web = findViewById(R.id.webView)
        etUrl = findViewById(R.id.etUrl)
        progress = findViewById(R.id.progressBar)
        tvWhere = findViewById(R.id.tvWhere)

        val prefs = getSharedPreferences("web", Context.MODE_PRIVATE)
        val saved = prefs.getString("url", "") ?: ""

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString = s.userAgentString + " DshTimetable/1.0"
        try {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        } catch (e: Exception) {
            // 老系统没有第三方 cookie 支持，忽略
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (url != null && url.isNotEmpty()) {
                    val title = view?.title ?: ""
                    tvWhere.text = if (title.isNotEmpty()) "当前页面：" + title else "当前页面：" + url
                    if (!url.startsWith("file:")) {
                        etUrl.setText(url)
                        prefs.edit().putString("url", url).apply()
                    }
                    // 自动更新时：如果当前是学校官网/门户，先自动跳到教务系统
                    if (autoMode && autoJump < 3 && looksPortal(url)) {
                        autoJump++
                        val known = knownJw(url)
                        if (known != null) {
                            tvWhere.text = "当前页面：正在跳转教务系统…"
                            web.loadUrl(known)
                            super.onPageFinished(view, url)
                            return
                        }
                        findJwLink()
                    }
                    // 自动更新时：已经进到教务系统了，就自动开始全周扫描（不用手点）
                    if (autoMode && !autoScanStarted && !notifiedExpired) {
                        val low0 = url.lowercase()
                        val inJw = low0.contains("ngjw") || low0.contains("/admin/") ||
                            low0.contains("jwxt") || low0.contains("zhjw") || low0.contains("urp")
                        if (inJw && !low0.contains("cas/login") && !low0.contains("/login")) {
                            autoScanStarted = true
                            view?.postDelayed({ scanAllWeeks() }, 1200)
                        }
                    }
                    // 自动更新时停在登录页 = 登录态失效，直接发通知
                    if (autoMode && !notifiedExpired) {
                        val low = url.lowercase()
                        val looksLogin = low.contains("cas/login") || low.contains("/login") ||
                            low.contains("sso") || low.contains("authservice")
                        val titleLooksLogin = title.contains("登录")
                        if (looksLogin || titleLooksLogin) {
                            notifiedExpired = true
                            notifyExpired(url)
                        }
                    }
                }
                super.onPageFinished(view, url)
            }
        }
        web.webChromeClient = WebChromeClient()

        // 引导页上的「快速入口」走这个桥接来跳转：WebView 里点 <a> 有时会被拦掉，
        // 直接调 loadUrl 最稳；顺便把「识别本页课表」也暴露给页面。
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun open(u: String) {
                runOnUiThread { if (u.isNotEmpty()) web.loadUrl(u) }
            }

            @android.webkit.JavascriptInterface
            fun progress(w: Int, tw: Int) {
                runOnUiThread {
                    if (silentMode) {
                        try { Notifier.notifyUpdating(this@WebImportActivity, "正在读取第 " + w + " / " + tw + " 周…", w, tw, false) } catch (e: Throwable) { }
                    }
                    scanTitle?.text = "正在识别课表"
                    scanText?.text = "正在读取第 " + w + " / " + tw + " 周…"
                    scanBar?.max = if (tw > 0) tw else 100
                    scanBar?.progress = w
                    scanLayer?.visibility = android.view.View.VISIBLE
                }
            }

            @android.webkit.JavascriptInterface
            fun scan() {
                runOnUiThread { scanPage() }
            }

            // 全部周次扫描完成后的回调（页面内 Promise 完成后主动送回结果）
            @android.webkit.JavascriptInterface
            fun weeks(json: String) {
                runOnUiThread {
                    awaitingWeeks = false
                    progress.visibility = View.GONE
                    tvWhere.text = "当前页面：" + (web.title ?: "")
                    if (json.isNotEmpty() && json != "null") handleScan(json) else scanPage()
                }
            }
        }, "Dsh")

        autoMode = intent?.getBooleanExtra("auto", false) ?: false
        val pushed = intent?.getStringExtra("url") ?: ""
        val target = if (pushed.isNotEmpty()) pushed else saved
        if (target.isEmpty()) {
            etUrl.setText("https://ngjw.nyist.edu.cn/admin/caslogin")
            web.loadUrl("file:///android_asset/import_start.html")
        } else {
            etUrl.setText(target)
            web.loadUrl(target)
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnGo).setOnClickListener {
            val u = etUrl.text.toString().trim()
            if (u.isEmpty()) {
                toast("先输入教务系统网址")
            } else {
                web.loadUrl(if (u.startsWith("http")) u else "https://" + u)
            }
        }
        scanLayer = findViewById(R.id.scanLayer)
        scanTitle = findViewById(R.id.scanTitle)
        scanText = findViewById(R.id.scanText)
        scanBar = findViewById(R.id.scanBar)
        findViewById<Button>(R.id.btnCancelScan).setOnClickListener {
            try { web.evaluateJavascript("window.__dshScanCancel=true", null) } catch (e: Throwable) { }
            awaitingWeeks = false
            hideScanLayer()
            Toast.makeText(this, "已取消识别", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener { scanAllWeeks() }
        // 长按「识别本页课表」：把当前页面 HTML + 解析结果存到 App 的外部目录，方便离线排查解析问题
        findViewById<Button>(R.id.btnScan).setOnLongClickListener {
            dumpPage()
            true
        }
        findViewById<Button>(R.id.btnPasteImport).setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
    }

    private fun showScanLayer(title: String, text: String, max: Int, progress: Int) {
        runOnUiThread {
            scanTitle?.text = title
            scanText?.text = text
            scanBar?.max = if (max > 0) max else 100
            scanBar?.progress = progress
            scanLayer?.visibility = android.view.View.VISIBLE
        }
    }

    private fun hideScanLayer() {
        runOnUiThread { scanLayer?.visibility = android.view.View.GONE }
    }

    private fun notifyExpired(pageUrl: String) {
        try { Notifier.clearUpdating(this) } catch (e: Throwable) { }
        val slot = Store.slot(this, Store.activeId(this))
        val site = slot?.site ?: ""
        val url = if (pageUrl.isNotEmpty()) pageUrl else (slot?.url ?: "")
        Notifier.notifyLoginExpired(this, url, site)
        Toast.makeText(this, "登录失效了，已发通知提醒你", Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    /** 把当前网页 HTML 存到 /sdcard/Android/data/<pkg>/files/last_page.html */
    private fun dumpPage() {
        try {
            web.evaluateJavascript(
                "(function(){try{return document.documentElement.outerHTML}catch(e){return ''}})()"
            ) { raw: String? ->
                try {
                    var html = raw ?: ""
                    if (html.startsWith("\"")) {
                        val v = org.json.JSONTokener(html).nextValue()
                        if (v is String) html = v
                    }
                    val dir = getExternalFilesDir(null)
                    if (dir != null) {
                        java.io.File(dir, "last_page.html").writeText(html)
                        toast("页面已保存：" + dir.absolutePath + "/last_page.html（" + html.length + " 字）")
                    }
                } catch (e: Exception) {
                    toast("保存失败：" + e.message)
                }
            }
        } catch (e: Exception) {
            toast("保存失败：" + e.message)
        }
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    /** 把 parse.js 注入当前页面跑一遍，拿回识别结果 */
    // 先把 1..N 周挨个切一遍，这样才能知道每门课「哪几周上」——单双周和只在
    // 特定周出现的课都靠这个。整轮最多十几秒，失败自动退回单页解析。
    private fun scanAllWeeks() {
        if (silentMode) {
            try { Notifier.notifyUpdating(this, "正在读取周次…", 0, 0, false) } catch (e: Throwable) { }
        }
        val js = try {
            assets.open("parse.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        if (js.isEmpty()) {
            toast("识别脚本缺失，请重新安装")
            return
        }
        if (awaitingWeeks) return
        awaitingWeeks = true
        val totalWeeks = Store.load(this).totalWeeks
        progress.visibility = View.VISIBLE
        tvWhere.text = "正在读取全部周次，请保持页面不动…"
        val code = "(function(){" + js +
            "\n;if(!window.__dshScanAllWeeks){Dsh.weeks('');return;}" +
            "window.__dshScanAllWeeks(" + totalWeeks + ").then(function(r){" +
            "Dsh.weeks(JSON.stringify(r));},function(e){Dsh.weeks('');});})()"
        web.evaluateJavascript(code) { }
        web.postDelayed({
            if (awaitingWeeks) {
                awaitingWeeks = false
                progress.visibility = View.GONE
                tvWhere.text = "当前页面：" + (web.title ?: "")
                scanPage()
            }
        }, 60000)
    }

    private fun scanPage() {
        val js = try {
            assets.open("parse.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        if (js.isEmpty()) {
            toast("识别脚本缺失，请重新安装")
            return
        }
        val totalWeeks = Store.load(this).totalWeeks
        val code = "(function(){" + js + "\n;return window.__dshScan(null,null," + totalWeeks + ");})()"
        progress.visibility = View.VISIBLE
        web.evaluateJavascript(code) { raw ->
            progress.visibility = View.GONE
            handleScan(raw)
        }
    }

    private fun handleScan(raw: String?) {
        hideScanLayer()
        if (raw == null || raw.isEmpty() || raw == "null") {
            fail("这一页还没加载好，或者页面里没有课表。\n\n提示：先登录，进到「我的课表」，等课表显示完整再点识别。")
            return
        }
        val text = try {
            val v = JSONTokener(raw).nextValue()
            if (v is String) v else raw
        } catch (e: Exception) {
            raw
        }
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            fail("识别结果读不出来，可能页面还没加载完，稍等两秒再点一次。")
            return
        }
        if (!obj.optBoolean("ok")) {
            fail("这一页没找到课表表格。\n\n可以试试：\n① 等课表完全显示后再点识别；\n② 用下面的「粘贴文字导入（备用）」；\n③ 有些教务系统是图片课表，只能手输或粘贴文字。")
            return
        }
        pendingTermStart = obj.optString("termStart", "")
        pendingTotalWeeks = obj.optInt("totalWeeks", 0)
        pendingPeriods.clear()
        val pa = obj.optJSONArray("periods")
        if (pa != null) {
            for (i in 0 until pa.length()) {
                val o = pa.optJSONObject(i) ?: continue
                val no = o.optInt("no", 0)
                val st = o.optString("start", "")
                val en = o.optString("end", "")
                if (no >= 1 && st.matches(Regex("\\d{2}:\\d{2}")) && en.matches(Regex("\\d{2}:\\d{2}"))) {
                    pendingPeriods[no] = st to en
                }
            }
        }
        val arr = obj.optJSONArray("courses") ?: JSONArray()
        val list = ArrayList<Course>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val st = o.optInt("start", 1)
            val en = o.optInt("end", st)
            list.add(
                Course(
                    name = name,
                    room = o.optString("room").trim(),
                    teacher = o.optString("teacher").trim(),
                    day = Math.max(0, Math.min(6, o.optInt("day", 0))),
                    start = Math.max(1, Math.min(st, en)),
                    end = Math.max(st, en),
                    weeks = o.optString("weeks").ifEmpty { "1-" + totalWeeksOf() },
                    color = o.optInt("color", i % 8)
                )
            )
        }
        if (list.isEmpty()) {
            fail("识别到 0 门课，可能这一页不是课表页。")
            return
        }
        if (autoMode) {
            if (silentMode) {
                try { Notifier.notifyUpdating(this, "已更新 " + list.size + " 门课程", 1, 0, true) } catch (e: Throwable) { }
                android.os.Handler(mainLooper).postDelayed({
                    try { Notifier.clearUpdating(this) } catch (e: Throwable) { }
                }, 4000)
            }
            applyCourses(list, true)
            android.widget.Toast.makeText(this, "已自动更新 " + list.size + " 门课程", android.widget.Toast.LENGTH_LONG).show()
        } else {
            showPreview(list)
        }
    }

    private var pendingTermStart = ""
    private val pendingPeriods = HashMap<Int, Pair<String, String>>()
    private var awaitingWeeks = false
    private var pendingTotalWeeks = 0

    private fun totalWeeksOf(): String {
        return Store.load(this).totalWeeks.toString()
    }

    private fun showPreview(list: List<Course>) {
        val sb = StringBuilder()
        for (i in 0 until Math.min(list.size, 12)) {
            val c = list[i]
            val d = if (c.day >= 0 && c.day < dayNames.length) dayNames.substring(c.day, c.day + 1) else "?"
            sb.append("周").append(d).append("  第").append(c.start)
            if (c.end != c.start) sb.append("-").append(c.end)
            sb.append("节  ").append(c.name)
            if (c.room.isNotEmpty()) sb.append("（").append(c.room).append("）")
            sb.append("\n")
        }
        if (list.size > 12) sb.append("… 还有 ").append(list.size - 12).append(" 门\n")

        AlertDialog.Builder(this)
            .setTitle("识别到 " + list.size + " 门课程")
            .setMessage(sb.toString().trim())
            .setPositiveButton("替换全部") { _, _ -> applyCourses(list, true) }
            .setNeutralButton("追加") { _, _ -> applyCourses(list, false) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyCourses(list: List<Course>, replace: Boolean) {
        val t = Store.load(this)
        if (replace) {
            t.courses = list.toMutableList()
        } else {
            val seen = HashSet<String>()
            for (c in t.courses) seen.add(c.day.toString() + "|" + c.start + "|" + c.name)
            for (c in list) {
                val k = c.day.toString() + "|" + c.start + "|" + c.name
                if (!seen.contains(k)) {
                    t.courses.add(c)
                    seen.add(k)
                }
            }
        }
        if (pendingTermStart.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) t.termStart = pendingTermStart
        // 节次时间也一起同步（页面只渲染到第 10 节，其余保留原值）
        for ((no, se) in pendingPeriods) {
            while (t.periods.size < no) t.periods.add(Period(t.periods.size + 1, "", ""))
            t.periods[no - 1] = Period(no, se.first, se.second)
        }
        if (pendingTotalWeeks > 0 && pendingTotalWeeks != t.totalWeeks) t.totalWeeks = pendingTotalWeeks
        Store.save(this, t)
        try { Store.markSynced(this, Store.activeId(this)) } catch (e: Throwable) { }
        try {
            val dir = getExternalFilesDir(null)
            if (dir != null) {
                java.io.File(dir, "timetable.json").writeText(JsonCodec.toJson(t), Charsets.UTF_8)
            }
        } catch (e: Exception) {
        }
        try {
            com.dsh.timetable.widget.TodayWidgetProvider.refreshAll(this)
        } catch (e: Exception) {
            // 没有小组件实例时忽略
        }
        toast("已写入 " + t.courses.size + " 门课程")
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun fail(msg: String) {
        hideScanLayer()
        AlertDialog.Builder(this)
            .setTitle("没识别到课表")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .setNeutralButton("改用粘贴导入") { _, _ ->
                startActivity(Intent(this, ImportActivity::class.java))
            }
            .show()
    }
}