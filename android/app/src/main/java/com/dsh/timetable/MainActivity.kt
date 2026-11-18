package com.dsh.timetable

import com.dsh.timetable.widget.TodayWidgetProvider
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import java.util.Calendar

class MainActivity : Activity() {

    private var lastActiveId: String? = null
    private lateinit var weekView: WeekView
    private lateinit var tvSub: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvWeek: TextView
    private lateinit var tvStatus: TextView

    private var previewOnly = false
    private var data: Timetable = Timetable()
    private var week = 1
    private var autoUpdateStarted = false
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 自检用：am start ... --es preview widget1x2 直接显示小组件 1×2 的位图
        val pv = intent?.getStringExtra("preview")
        if (pv == "widget1x2" || pv == "widget2x3") {
            previewOnly = true
            val iv = android.widget.ImageView(this)
            // CENTER：按位图原始像素显示，方便量真实尺寸（FIT_CENTER 会把位图拉满屏，看不出适配）
            iv.scaleType = android.widget.ImageView.ScaleType.CENTER
            iv.setBackgroundColor(android.graphics.Color.parseColor("#E7EEF5"))
            val ph = intent.getIntExtra("phour", -1)
            val pm = intent.getIntExtra("pmin", -1)
            val pday = intent.getIntExtra("pday", -1)
            val cal = Calendar.getInstance()
            if (ph >= 0) cal.set(Calendar.HOUR_OF_DAY, ph)
            if (pm >= 0) cal.set(Calendar.MINUTE, pm)
            if (pday >= 0) cal.set(Calendar.DAY_OF_WEEK, pday)
            val pwd = intent.getIntExtra("pw", if (pv == "widget2x3") 168 else 320)
            val phh = intent.getIntExtra("ph", if (pv == "widget2x3") 300 else 108)
            iv.setImageBitmap(
                if (pv == "widget2x3") WidgetPainter.todayTall(this, pwd, phh, cal)
                else WidgetPainter.today(this, pwd, phh, cal)
            )
            setContentView(iv)
            return
        }
        setContentView(R.layout.activity_main)

        // 自检用：--es probeUpdate <url> 走一遍 检查→下载（不安装），结果用 Toast 显示
        intent?.getStringExtra("probeUpdate")?.let { probeUrl ->
            Thread {
                try {
                    val info = UpdateChecker.fetch(probeUrl)
                    val dest = java.io.File(cacheDir, "probe.apk")
                    UpdateChecker.download(info.apkUrl, dest) { _, _ -> }
                    val msg = "更新源OK v" + info.versionName + " (" + info.versionCode + ")，下载 " +
                        (dest.length() / 1024) + " KB"
                    java.io.File(getExternalFilesDir(null), "probe.txt").writeText(msg)
                    runOnUiThread { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() }
                } catch (e: Throwable) {
                    java.io.File(getExternalFilesDir(null), "probe.txt")
                        .writeText("FAIL: " + (e.message ?: e.toString()))
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "更新源自检失败：" + e.message,
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        // 自检用：--ez testInstall true 把 /sdcard/Android/data/<pkg>/files/test.apk 交给安装器
        if (intent?.getBooleanExtra("testInstall", false) == true) {
            val src = java.io.File(getExternalFilesDir(null), "test.apk")
            val dst = java.io.File(cacheDir, "selftest.apk")
            try {
                src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                UpdateHelper.installFile(this, dst)
            } catch (e: Throwable) {
                android.widget.Toast.makeText(this, "selftest: ${e.message}",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }

        // 自检用：--ez openSettings true 直接打开设置页（SettingsActivity 不导出，shell 起不来）
        if (intent?.getBooleanExtra("openSettings", false) == true) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        weekView = findViewById(R.id.weekView)
        tvSub = findViewById(R.id.tvSub)
        tvClock = findViewById(R.id.tvClock)
        tvCount = findViewById(R.id.tvCount)
        tvWeek = findViewById(R.id.tvWeek)
        tvStatus = findViewById(R.id.tvStatus)

        data = Store.load(this)
        week = Weeks.currentWeek(data)

        findViewById<android.widget.ImageButton>(R.id.btnPrev).setOnClickListener { goWeek(-1) }
        findViewById<android.widget.ImageButton>(R.id.btnNext).setOnClickListener { goWeek(1) }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            startActivity(Intent(this, WebImportActivity::class.java))
        }
        // Android 13+ 通知权限（登录失效提醒要用）
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val need = checkSelfPermission("android.permission.POST_NOTIFICATIONS") !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (need) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        }
        // 自检用：--es demoNotif 1 直接弹一条「登录失效」通知
        demoTry()
        if (intent?.getStringExtra("demoNotif") != null) {
            Notifier.notifyLoginExpired(this, "https://ngjw.nyist.edu.cn/admin/caslogin", "南阳理工教务系统")
        }

        findViewById<Button>(R.id.btnSetting).text = "设置"
        findViewById<Button>(R.id.btnSetting).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showCourseEditor(null) }

        weekView.onCourseClick = { c -> showCourseCard(c) }
        // 左右滑动切换上一周 / 下一周
        weekView.onWeekSwipe = { d -> goWeek(d) }
        // 点「第 N 周 · 周X · 月日」回到本周（定位到今天）
        findViewById<TextView>(R.id.tvSub).setOnClickListener { goToday() }
    }

    /** 自检用：--es demoAuto <url> 走一次「自动更新」路径，撞到登录页就会发通知 */
    private fun demoTry() {
        val u = intent?.getStringExtra("demoAuto") ?: return
        val silent = intent?.getBooleanExtra("demoSilent", false) ?: false
        if (silent) {
            AutoUpdateService.start(this, u, "")
            return
        }
        val i = Intent(this, WebImportActivity::class.java)
        i.putExtra("url", u)
        i.putExtra("auto", true)
        startActivity(i)
    }

    override fun onResume() {
        super.onResume()
        if (previewOnly) return
        data = Store.load(this)
        // 切换了课表（在设置里换了当前课表）就跳回本周，避免停在一个不属于这份课表的周次
        val aid = Store.activeId(this)
        if (aid != lastActiveId) {
            lastActiveId = aid
            week = Weeks.currentWeek(data)
        }
        if (week < 1 || week > data.totalWeeks) week = Weeks.currentWeek(data)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        if (previewOnly) return
        handler.removeCallbacks(ticker)
    }

    /** 回到本周：把周次切回当前周，今天的列本来就是高亮的 */
    private fun goToday() {
        val target = Weeks.currentWeek(data)
        if (week == target) {
            Toast.makeText(this, "已经是本周了", Toast.LENGTH_SHORT).show()
            render()
            return
        }
        week = target
        render()
        weekView.fadeIn(150)
        Toast.makeText(this, "已回到第 " + target + " 周", Toast.LENGTH_SHORT).show()
    }

    /** 翻到上一周 / 下一周，滑到头就不再动 */
    private fun goWeek(delta: Int) {
        val target = week + delta
        if (target < 1 || target > data.totalWeeks) return
        week = target
        render()
    }

    private fun render() {
        if (previewOnly) return
        val now = Calendar.getInstance()
        val todayIdx = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        tvSub.text = "第 " + week + " 周 · " + names[todayIdx] + " · " +
            (now.get(Calendar.MONTH) + 1) + "月" + now.get(Calendar.DAY_OF_MONTH) + "日"
        tvClock.text = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        tvWeek.text = "第" + week + "周"

        val cw = Weeks.currentWeek(data)
        val todayList = data.courses
            .filter { it.day == todayIdx && Weeks.inWeek(it.weeks, cw) }
            .sortedBy { it.start }
        tvCount.text = "今天 " + todayList.size + " 节课"

        tvStatus.text = statusOf(now, todayIdx, cw)
        // 自动更新到点了就在底部提示一下，点这条提示直接进网页导入
        val slot = Store.slot(this, Store.activeId(this))
        val stale = slot != null && slot.auto && slot.url.isNotEmpty() &&
            (slot.syncedAt == 0L ||
                System.currentTimeMillis() - slot.syncedAt > slot.intervalDays.toLong() * 24L * 3600L * 1000L)
        if (stale && slot != null && !autoUpdateStarted) {
            // 到期就自己在后台静默更新一次（透明页，只在通知栏显示「正在自动更新」）
            autoUpdateStarted = true
            try {
                AutoUpdateService.start(this, slot.url, slot.site)
            } catch (e: Throwable) {
            }
        }
        if (stale && slot != null) {
            val who = if (slot.site.isNotEmpty()) slot.site else "官网"
            tvStatus.text = "该更新课表了：" + who + " 已超过 " + slot.intervalDays + " 天 · 点这里去更新"
            tvStatus.setOnClickListener {
                val i = Intent(this, WebImportActivity::class.java)
                i.putExtra("url", slot.url)
                startActivity(i)
            }
        } else {
            tvStatus.setOnClickListener(null)
        }
        weekView.setData(data, week)
    }

    private fun statusOf(now: Calendar, todayIdx: Int, cw: Int): String {
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val list = data.courses
            .filter { it.day == todayIdx && Weeks.inWeek(it.weeks, cw) }
            .sortedBy { it.start }
        for (c in list) {
            val s = minutesOf(c.start, true)
            val e = minutesOf(c.end, false)
            if (s < 0 || e < 0) continue
            if (mins >= s && mins <= e) {
                return "正在上 · " + c.name + " " + c.room + " · 还剩 " + (e - mins) + " 分钟"
            }
        }
        for (c in list) {
            val s = minutesOf(c.start, true)
            if (s > mins) {
                return "下一节 " + c.name + " · " + c.room + " · " + (s - mins) + " 分钟后"
            }
        }
        return if (list.isEmpty()) "今天没有课，好好休息" else "今天的课都上完啦"
    }

    private fun minutesOf(no: Int, isStart: Boolean): Int {
        val p = data.periods.getOrNull(no - 1) ?: return -1
        val text = if (isStart) p.start else p.end
        val parts = text.split(":")
        if (parts.size < 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        return h * 60 + m
    }


    /** 点课程卡片后，屏幕中间浮出一张圆角卡：课名 / 教室 / 节次时间 */
    private fun showCourseCard(c: Course) {
        val dp = resources.displayMetrics.density
        fun d(v: Float) = (v * dp)

        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.cornerRadius = d(24f)
        bg.setColor(Color.WHITE)
        bg.setStroke(d(1f).toInt(), Color.parseColor("#E9EEF5"))
        card.background = bg
        card.setPadding(d(22f).toInt(), d(20f).toInt(), d(22f).toInt(), d(20f).toInt())
        card.elevation = d(12f)

        // 顶部：节次 + 时间
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL

        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.VERTICAL
        val chipBg = GradientDrawable()
        chipBg.cornerRadius = d(14f)
        chipBg.setColor(Color.parseColor("#F2F7FE"))
        chip.background = chipBg
        chip.setPadding(d(12f).toInt(), d(8f).toInt(), d(12f).toInt(), d(8f).toInt())
        chip.gravity = Gravity.CENTER

        val tvTime = TextView(this)
        tvTime.text = data.periods.getOrNull(c.start - 1)?.start ?: "--:--"
        tvTime.setTextColor(Color.parseColor("#0069D9"))
        tvTime.textSize = 19f
        tvTime.gravity = Gravity.CENTER
        chip.addView(tvTime)

        val tvPeriod = TextView(this)
        tvPeriod.text = "第 " + c.start + "-" + c.end + " 节"
        tvPeriod.setTextColor(Color.parseColor("#8A94A6"))
        tvPeriod.textSize = 9.5f
        tvPeriod.gravity = Gravity.CENTER
        chip.addView(tvPeriod)

        head.addView(chip)

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.setPadding(d(14f).toInt(), 0, 0, 0)

        val tvName = TextView(this)
        tvName.text = c.name
        tvName.setTextColor(Color.parseColor("#1D2433"))
        tvName.textSize = 18f
        tvName.setTypeface(tvName.typeface, android.graphics.Typeface.BOLD)
        info.addView(tvName)

        val tvRoom = TextView(this)
        tvRoom.text = c.room
        tvRoom.setTextColor(Color.parseColor("#5B6678"))
        tvRoom.textSize = 12f
        tvRoom.setPadding(0, d(4f).toInt(), 0, 0)
        info.addView(tvRoom)

        val tvSpan = TextView(this)
        val p1 = data.periods.getOrNull(c.start - 1)?.start ?: ""
        val p2 = data.periods.getOrNull(c.end - 1)?.end ?: ""
        tvSpan.text = "第 " + c.start + "-" + c.end + " 节 · " + p1 + " - " + p2
        tvSpan.setTextColor(Color.parseColor("#8A94A6"))
        tvSpan.textSize = 10.5f
        tvSpan.setPadding(0, d(6f).toInt(), 0, 0)
        info.addView(tvSpan)

        if (c.teacher.isNotEmpty() || c.weeks.isNotEmpty()) {
            val tvT = TextView(this)
            val wk = if (c.weeks.isEmpty()) "" else " · " + c.weeks + " 周"
            tvT.text = c.teacher + wk
            tvT.setTextColor(Color.parseColor("#A8B2C1"))
            tvT.textSize = 10f
            tvT.setPadding(0, d(3f).toInt(), 0, 0)
            info.addView(tvT)
        }

        head.addView(info)
        card.addView(head)

        // 底部：编辑 / 关闭
        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.setPadding(0, d(14f).toInt(), 0, 0)

        val btnClose = TextView(this)
        btnClose.text = "关闭"
        btnClose.setTextColor(Color.parseColor("#5B6678"))
        btnClose.textSize = 12f
        btnClose.gravity = Gravity.CENTER
        val closeBg = GradientDrawable()
        closeBg.cornerRadius = d(12f)
        closeBg.setColor(Color.parseColor("#F2F5FA"))
        btnClose.background = closeBg
        btnClose.setPadding(d(18f).toInt(), d(9f).toInt(), d(18f).toInt(), d(9f).toInt())
        btnClose.setOnClickListener { dlg.dismiss() }
        btnRow.addView(btnClose)

        val btnEdit = TextView(this)
        btnEdit.text = "编辑"
        btnEdit.setTextColor(Color.WHITE)
        btnEdit.textSize = 12f
        btnEdit.gravity = Gravity.CENTER
        val editBg = GradientDrawable()
        editBg.cornerRadius = d(12f)
        editBg.setColor(Color.parseColor("#0069D9"))
        btnEdit.background = editBg
        btnEdit.setPadding(d(22f).toInt(), d(9f).toInt(), d(22f).toInt(), d(9f).toInt())
        val lpEdit = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lpEdit.leftMargin = d(10f).toInt()
        btnEdit.layoutParams = lpEdit
        btnEdit.setOnClickListener {
            dlg.dismiss()
            showCourseEditor(c)
        }
        btnRow.addView(btnEdit)

        val lpClose = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnClose.layoutParams = lpClose
        card.addView(btnRow)

        card.setOnClickListener { dlg.dismiss() }
        dlg.setContentView(card)

        val w = dlg.window
        if (w != null) {
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.setLayout(d(320f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
            w.setDimAmount(0.25f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.setCanceledOnTouchOutside(true)
        dlg.setOnShowListener {
            val dv = dlg.window?.decorView
            if (dv != null) {
                // 从 0.85 起淡入：即使动画被跳过，卡片也是可见的
                dv.alpha = 0.85f
                dv.translationY = d(6f)
                dv.animate().alpha(1f).translationY(0f).setDuration(160).start()
            }
        }
        dlg.show()
    }
    /** 点卡片里的「编辑」：改课名、地点、星期、起止节次，保存后写回当前课表 */
    private fun showCourseEditor(c: Course?) {
        val dp = resources.displayMetrics.density
        fun d(v: Float) = (v * dp).toInt()

        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.cornerRadius = d(24f).toFloat()
        bg.setColor(Color.WHITE)
        bg.setStroke(d(1f), Color.parseColor("#E9EEF5"))
        box.background = bg
        box.setPadding(d(20f), d(18f), d(20f), d(16f))

        val title = TextView(this)
        title.text = if (c == null) "添加课程" else "编辑课程"
        title.setTextColor(Color.parseColor("#1D2433"))
        title.textSize = 16f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        box.addView(title)

        fun label(t: String) {
            val tv = TextView(this)
            tv.text = t
            tv.setTextColor(Color.parseColor("#8A94A6"))
            tv.textSize = 10.5f
            tv.setPadding(0, d(12f), 0, d(4f))
            box.addView(tv)
        }

        label("课程名")
        val etName = EditText(this)
        etName.setText(if (c == null) "" else c.name)
        etName.textSize = 13f
        etName.setSingleLine(true)
        box.addView(etName)

        label("上课地点")
        val etRoom = EditText(this)
        etRoom.setText(if (c == null) "" else c.room)
        etRoom.textSize = 13f
        etRoom.setSingleLine(true)
        box.addView(etRoom)

        label("上课周数")
        val etWeeksCourse = EditText(this)
        etWeeksCourse.setText(if (c == null) "1-" + data.totalWeeks else c.weeks)
        etWeeksCourse.textSize = 13f
        etWeeksCourse.setSingleLine(true)
        etWeeksCourse.hint = "1-16 / 1-8,10-16 / 结尾加「单」或「双」"
        box.addView(etWeeksCourse)

        val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        label("星期")
        val spDay = Spinner(this)
        spDay.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dayNames)
        spDay.setSelection(if (c == null) ((Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7) else if (c.day in 0..6) c.day else 0)
        box.addView(spDay)

        val maxP = if (data.periods.isEmpty()) 12 else data.periods.size
        val periodNames = (1..maxP).map { "第 " + it + " 节" }.toTypedArray()
        val rowP = LinearLayout(this)
        rowP.orientation = LinearLayout.HORIZONTAL
        val colA = LinearLayout(this)
        colA.orientation = LinearLayout.VERTICAL
        colA.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val tvA = TextView(this)
        tvA.text = "开始"
        tvA.setTextColor(Color.parseColor("#8A94A6"))
        tvA.textSize = 10.5f
        colA.addView(tvA)
        val spStart = Spinner(this)
        spStart.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, periodNames)
        spStart.setSelection((if (c == null) 0 else c.start - 1).coerceIn(0, maxP - 1))
        colA.addView(spStart)
        rowP.addView(colA)

        val colB = LinearLayout(this)
        colB.orientation = LinearLayout.VERTICAL
        val lpB = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lpB.leftMargin = d(10f)
        colB.layoutParams = lpB
        val tvB = TextView(this)
        tvB.text = "结束"
        tvB.setTextColor(Color.parseColor("#8A94A6"))
        tvB.textSize = 10.5f
        colB.addView(tvB)
        val spEnd = Spinner(this)
        spEnd.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, periodNames)
        spEnd.setSelection((if (c == null) 1 else c.end - 1).coerceIn(0, maxP - 1))
        colB.addView(spEnd)
        rowP.addView(colB)
        box.addView(rowP)

        val rowBtn = LinearLayout(this)
        rowBtn.orientation = LinearLayout.HORIZONTAL
        rowBtn.setPadding(0, d(16f), 0, 0)

        if (c != null) {
        val btnDel = TextView(this)
        btnDel.text = "删除"
        btnDel.setTextColor(Color.parseColor("#E2564A"))
        btnDel.textSize = 12f
        btnDel.gravity = Gravity.CENTER
        val delBg = GradientDrawable()
        delBg.cornerRadius = d(12f).toFloat()
        delBg.setColor(Color.parseColor("#FDEDEC"))
        btnDel.background = delBg
        btnDel.setPadding(d(16f), d(9f), d(16f), d(9f))
        btnDel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnDel.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("删除这门课？")
                .setMessage("只删当前课表里的这一条，重新导入可以恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    deleteCourse(c)
                    dlg.dismiss()
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        rowBtn.addView(btnDel)
        }

        val btnCancel = TextView(this)
        btnCancel.text = "取消"
        btnCancel.setTextColor(Color.parseColor("#5B6678"))
        btnCancel.textSize = 12f
        btnCancel.gravity = Gravity.CENTER
        val cbBg = GradientDrawable()
        cbBg.cornerRadius = d(12f).toFloat()
        cbBg.setColor(Color.parseColor("#F2F5FA"))
        btnCancel.background = cbBg
        btnCancel.setPadding(d(18f), d(9f), d(18f), d(9f))
        val lpCancel = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lpCancel.leftMargin = d(10f)
        btnCancel.layoutParams = lpCancel
        btnCancel.setOnClickListener { dlg.dismiss() }
        rowBtn.addView(btnCancel)

        val btnOk = TextView(this)
        btnOk.text = "保存"
        btnOk.setTextColor(Color.WHITE)
        btnOk.textSize = 12f
        btnOk.gravity = Gravity.CENTER
        val okBg = GradientDrawable()
        okBg.cornerRadius = d(12f).toFloat()
        okBg.setColor(Color.parseColor("#0069D9"))
        btnOk.background = okBg
        btnOk.setPadding(d(18f), d(9f), d(18f), d(9f))
        val lpOk = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lpOk.leftMargin = d(10f)
        btnOk.layoutParams = lpOk
        btnOk.setOnClickListener {
            val st = spStart.selectedItemPosition + 1
            val en = spEnd.selectedItemPosition + 1
            if (st > en) {
                Toast.makeText(this, "开始节次不能大于结束节次", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val nm = etName.text.toString().trim()
            if (nm.isEmpty()) {
                Toast.makeText(this, "课程名不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val wk = Weeks.normalize(etWeeksCourse.text.toString(), data.totalWeeks)
            if (c == null) {
                addCourse(nm, etRoom.text.toString().trim(), spDay.selectedItemPosition, st, en, wk)
            } else {
                saveCourseEdit(c, nm, etRoom.text.toString().trim(), spDay.selectedItemPosition, st, en, wk)
            }
            dlg.dismiss()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }
        rowBtn.addView(btnOk)
        box.addView(rowBtn)

        val scroll = ScrollView(this)
        scroll.addView(box)
        dlg.setContentView(scroll)

        val w = dlg.window
        if (w != null) {
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.setLayout(d(330f), (520 * dp).toInt())
            w.setGravity(Gravity.CENTER)
            w.setDimAmount(0.3f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dlg.setCanceledOnTouchOutside(true)
        dlg.show()
    }

    /** 手动添加一门课 */
    private fun addCourse(name: String, room: String, day: Int, start: Int, end: Int, weeks: String) {
        val color = (data.courses.size) % Defaults.COLORS.size
        val c = Course(name = name, room = room, teacher = "", day = day, start = start, end = end, weeks = weeks, color = color)
        val list = ArrayList<Course>()
        list.addAll(data.courses)
        list.add(c)
        data = data.copy(courses = list)
        Store.save(this, data)
        render()
        try {
            TodayWidgetProvider.refreshAll(this)
        } catch (e: Throwable) {
        }
    }

    /** 删掉某门课（只动当前课表这一条） */
    private fun deleteCourse(c: Course) {
        val list = ArrayList<Course>()
        for (x in data.courses) {
            if (x !== c) list.add(x)
        }
        data = data.copy(courses = list)
        Store.save(this, data)
        render()
        try {
            TodayWidgetProvider.refreshAll(this)
        } catch (e: Throwable) {
        }
    }

    /** 把编辑结果写回当前课表并刷新界面与小组件 */
    private fun saveCourseEdit(old: Course, name: String, room: String, day: Int, start: Int, end: Int, weeks: String) {
        val list = ArrayList<Course>()
        for (x in data.courses) {
            if (x === old) {
                list.add(x.copy(name = name, room = room, day = day, start = start, end = end, weeks = weeks))
            } else {
                list.add(x)
            }
        }
        data = data.copy(courses = list)
        Store.save(this, data)
        render()
        try {
            TodayWidgetProvider.refreshAll(this)
        } catch (e: Throwable) {
        }
    }

    private fun showTermDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, 0)

        val etStart = EditText(this)
        etStart.hint = "开学第一周的周一，如 2026-09-01"
        etStart.setText(data.termStart)
        box.addView(etStart)

        val etWeeks = EditText(this)
        etWeeks.hint = "总周数"
        etWeeks.setText(data.totalWeeks.toString())
        box.addView(etWeeks)

        AlertDialog.Builder(this)
            .setTitle("学期设置")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val s = etStart.text.toString().trim()
                if (Weeks.parseDate(s) == null) {
                    Toast.makeText(this, "日期格式应为 2026-09-01", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val w = etWeeks.text.toString().trim().toIntOrNull() ?: data.totalWeeks
                data.termStart = s
                data.totalWeeks = if (w < 1) 16 else w
                Store.save(this, data)
                week = Weeks.currentWeek(data)
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}