package com.dsh.timetable

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var updAddr: TextView

    companion object {
        private const val REQ_APK = 2001
    }


    private val d by lazy { resources.displayMetrics.density }
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFFF2F5FA.toInt())
        val pad = (14 * d).toInt()
        root.setPadding(pad, pad, pad, pad)

        // 标题行
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val tvTitle = TextView(this)
        tvTitle.text = "课表管理"
        tvTitle.textSize = 20f
        tvTitle.setTextColor(0xFF1D2433.toInt())
        tvTitle.paint.isFakeBoldText = true
        titles.addView(tvTitle)
        val tvSub = TextView(this)
        tvSub.text = "点一行查看详情、启用或设置自动更新，长按可复制 / 删除"
        tvSub.textSize = 11.5f
        tvSub.setTextColor(0xFF8A94A6.toInt())
        titles.addView(tvSub)
        head.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val btnDone = Button(this)
        btnDone.text = "完成"
        btnDone.textSize = 13f
        btnDone.isAllCaps = false
        btnDone.setTextColor(0xFF0069D9.toInt())
        btnDone.background = chip()
        btnDone.setOnClickListener { finish() }
        head.addView(btnDone, LinearLayout.LayoutParams((72 * d).toInt(), (36 * d).toInt()))
        root.addView(head)

        // 书架列表
        val scroll = ScrollView(this)
        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        scroll.addView(listBox)
        val lpScroll = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        )
        lpScroll.topMargin = (10 * d).toInt()
        root.addView(scroll, lpScroll)

        // 底部动作
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER_VERTICAL
        actions.addView(actionButton("新建空白课表") { createBlank() },
            LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f))
        actions.addView(actionButton("复制当前课表") { duplicateActive() },
            LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f).also { it.marginStart = (8 * d).toInt() })
        val lpAct = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lpAct.topMargin = (10 * d).toInt()
        root.addView(actions, lpAct)

        // 学期设置入口
        val termRow = TextView(this)
        termRow.text = "学期起始日与总周数  ›"
        termRow.textSize = 13f
        termRow.setTextColor(0xFF2C74C8.toInt())
        termRow.setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
        termRow.background = card()
        termRow.setOnClickListener { editTerm() }
        val lpTerm = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lpTerm.topMargin = (10 * d).toInt()
        root.addView(termRow, lpTerm)

        // 软件更新（在线）：检查 → 下载 → 系统安装器覆盖旧版
        val updCard = LinearLayout(this)
        updCard.orientation = LinearLayout.VERTICAL
        updCard.setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
        updCard.background = card()

        val updTitle = TextView(this)
        updTitle.text = "软件更新"
        updTitle.textSize = 13.5f
        updTitle.setTextColor(0xFF1D2433.toInt())
        updTitle.paint.isFakeBoldText = true
        updCard.addView(updTitle)

        val updVer = TextView(this)
        updVer.textSize = 11.5f
        updVer.setTextColor(0xFF8A94A6.toInt())
        updVer.setPadding(0, (3 * d).toInt(), 0, (9 * d).toInt())
        updVer.text = "当前版本 " + UpdateHelper.versionText(this) + " · 下载后自动覆盖旧版，课表不丢"
        updCard.addView(updVer)

        val updRow = LinearLayout(this)
        updRow.orientation = LinearLayout.HORIZONTAL
        val btnCheck = actionButton("检查更新") { doCheckUpdate() }
        updRow.addView(btnCheck, LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f))

        val btnUrl = Button(this)
        btnUrl.text = "更新地址"
        btnUrl.textSize = 13f
        btnUrl.isAllCaps = false
        btnUrl.setTextColor(0xFF0069D9.toInt())
        btnUrl.background = chip()
        btnUrl.setOnClickListener { editUpdateUrl() }
        updRow.addView(btnUrl, LinearLayout.LayoutParams((96 * d).toInt(), (40 * d).toInt())
            .also { it.marginStart = (8 * d).toInt() })
        updCard.addView(updRow)

        updAddr = TextView(this)
        updAddr.textSize = 11f
        updAddr.setTextColor(0xFF8A94A6.toInt())
        updAddr.setPadding(0, (7 * d).toInt(), 0, 0)
        val u = UpdateChecker.updateUrl(this)
        updAddr.text = if (UpdateChecker.isDefaultUrl(this)) "更新地址：$u（默认，走 GitHub）" else "更新地址：$u"
        updCard.addView(updAddr)

        val lpUpd = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT)
        lpUpd.topMargin = (10 * d).toInt()
        root.addView(updCard, lpUpd)

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (::listBox.isInitialized) listBox.removeAllViews()
        val active = Store.activeId(this)
        for (slot in Store.shelf(this)) {
            listBox.addView(row(slot, slot.id == active))
        }
    }

    private fun row(slot: ShelfSlot, isActive: Boolean): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val p = (14 * d).toInt()
        row.setPadding(p, p, p, p)
        row.background = if (isActive) activeCard() else card()

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = slot.name
        name.textSize = 15f
        name.setTextColor(0xFF1D2433.toInt())
        name.paint.isFakeBoldText = true
        left.addView(name)
        val meta = TextView(this)
        val t = JsonCodec.fromJson(slot.json)
        meta.text = if (t == null) "数据损坏" else
            (t.courses.size.toString() + " 门课 · " + t.totalWeeks + " 周 · 起始 " + t.termStart)
        meta.textSize = 11f
        meta.setTextColor(0xFF8A94A6.toInt())
        left.addView(meta)
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (isActive) {
            val chip = TextView(this)
            chip.text = "当前"
            chip.textSize = 11f
            chip.gravity = Gravity.CENTER
            chip.setTextColor(0xFF0069D9.toInt())
            chip.background = activeChip()
            chip.setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            row.addView(chip)
        }

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (8 * d).toInt()
        row.layoutParams = lp

        row.setOnClickListener {
            val i = Intent(this, TimetableDetailActivity::class.java)
            i.putExtra("id", slot.id)
            startActivity(i)
        }
        row.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(slot.name)
                .setItems(arrayOf("复制一份", "删除")) { _, which ->
                    when (which) {
                        0 -> {
                            val id = Store.duplicate(this, slot.id, slot.name + " 副本")
                            if (id != null) { Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show(); refresh() }
                        }
                        1 -> {
                            if (Store.remove(this, slot.id)) {
                                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show(); refresh()
                            } else {
                                Toast.makeText(this, "至少留一份课表", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .show()
            true
        }
        return row
    }

    private fun renameDialog(slot: ShelfSlot) {
        val et = EditText(this)
        et.setText(slot.name)
        val box = LinearLayout(this)
        box.setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), 0)
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) { Store.rename(this, slot.id, n); refresh() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createBlank() {
        val et = EditText(this)
        et.setText("新课表 " + (Store.shelf(this).size + 1))
        val box = LinearLayout(this)
        box.setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), 0)
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle("新建空白课表")
            .setView(box)
            .setPositiveButton("创建") { _, _ ->
                val name = et.text.toString().trim().ifEmpty { "新课表" }
                val t = Timetable()
                t.termStart = mondayOfToday()
                t.totalWeeks = 16
                t.periods = Defaults.periods()
                t.courses = ArrayList()
                val id = Store.add(this, name, t)
                Store.switchTo(this, id)
                Toast.makeText(this, "已新建并切换", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun duplicateActive() {
        val id = Store.activeId(this)
        val slot = Store.shelf(this).firstOrNull { it.id == id } ?: return
        if (Store.duplicate(this, id, slot.name + " 副本") != null) {
            Toast.makeText(this, "已复制当前课表", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun editTerm() {
        val data = Store.load(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), 0)
        val etStart = EditText(this)
        etStart.hint = "开学第一周周一，如 2026-09-07"
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
                    Toast.makeText(this, "日期格式应为 2026-09-07", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val w = etWeeks.text.toString().trim().toIntOrNull() ?: data.totalWeeks
                data.termStart = s
                data.totalWeeks = if (w < 1) 16 else w
                Store.save(this, data)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editUpdateUrl() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((18 * d).toInt(), (8 * d).toInt(), (18 * d).toInt(), 0)
        val et = EditText(this)
        et.setText(UpdateChecker.updateUrl(this))
        et.setSelection(et.text.length)
        et.hint = "https://example.com/update.json 或 .../app-debug.apk"
        et.textSize = 13f
        et.setSingleLine(false)
        box.addView(et)
        val tip = TextView(this)
        tip.text = "填 JSON 地址最准：{\"versionCode\":3,\"versionName\":\"1.2\",\"url\":\"APK 直链\"}\n" +
            "只填 .apk 直链也可以，只是没法自动比较版本号（会先问你一句）"
        tip.textSize = 11f
        tip.setTextColor(0xFF8A94A6.toInt())
        tip.setPadding(0, (8 * d).toInt(), 0, 0)
        box.addView(tip)
        AlertDialog.Builder(this)
            .setTitle("更新地址")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                UpdateChecker.setUpdateUrl(this, et.text.toString())
                val v = UpdateChecker.updateUrl(this)
                updAddr.text = if (UpdateChecker.isDefaultUrl(this)) "更新地址：$v（默认，走 GitHub）" else "更新地址：$v"
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                UpdateChecker.resetUrl(this)
                updAddr.text = "更新地址：" + UpdateChecker.DEFAULT_URL + "（默认，走 GitHub）"
                Toast.makeText(this, "已恢复默认地址", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doCheckUpdate() {
        val src = UpdateChecker.updateUrl(this)
        if (src.isEmpty()) {
            editUpdateUrl()
            return
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("检查更新")
            .setMessage("正在连接…")
            .setCancelable(true)
            .create()
        dlg.show()
        Thread {
            try {
                val info = UpdateChecker.fetch(src)
                val cur = UpdateChecker.currentVersionCode(this)
                runOnUiThread {
                    dlg.dismiss()
                    when {
                        info.versionCode <= 0 -> confirmUnknown(info)
                        info.versionCode <= cur -> Toast.makeText(
                            this, "已是最新版本（" + UpdateHelper.versionText(this) + "）",
                            Toast.LENGTH_LONG).show()
                        else -> confirmUpdate(info)
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlg.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("检查失败")
                        .setMessage("" + e.message + "\n\n可以检查：地址是否可直连（网盘分享页不行，要直链）、是否 https、是否被墙。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }.start()
    }

    private fun confirmUnknown(info: UpdateChecker.Info) {
        AlertDialog.Builder(this)
            .setTitle("这个链接没给版本号")
            .setMessage("直接下载并安装？系统会自动覆盖旧版。")
            .setPositiveButton("下载并安装") { _, _ -> startDownload(info) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmUpdate(info: UpdateChecker.Info) {
        val ver = if (info.versionName.isEmpty()) "新版本" else info.versionName
        val msg = StringBuilder("发现 " + ver + "（当前 " + UpdateHelper.versionText(this) + "）")
        if (info.notes.isNotEmpty()) msg.append("\n\n").append(info.notes)
        msg.append("\n\n下载完成后会自动调起安装，覆盖旧版本。")
        AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(msg.toString())
            .setPositiveButton("立即更新") { _, _ -> startDownload(info) }
            .setNegativeButton("以后再说", null)
            .show()
    }

    private fun startDownload(info: UpdateChecker.Info) {
        val prog = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        prog.max = 100
        val dlgProg = AlertDialog.Builder(this)
            .setTitle("正在下载新版本")
            .setView(prog)
            .setCancelable(false)
            .create()
        dlgProg.show()
        val dest = java.io.File(cacheDir, "update.apk")
        Thread {
            try {
                UpdateChecker.download(info.apkUrl, dest) { done, total ->
                    val p = if (total > 0) (done * 100 / total).toInt() else 0
                    runOnUiThread { prog.progress = p }
                }
                runOnUiThread {
                    dlgProg.dismiss()
                    UpdateHelper.installFile(this, dest)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    dlgProg.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("下载失败")
                        .setMessage("" + e.message)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_APK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            UpdateHelper.installFromUri(this, uri)
        }
    }

    private fun mondayOfToday(): String {
        val c = java.util.Calendar.getInstance()
        val back = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        c.add(java.util.Calendar.DAY_OF_MONTH, -back)
        return String.format("%04d-%02d-%02d", c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 13f
        b.isAllCaps = false
        b.setTextColor(0xFFFFFFFF.toInt())
        b.background = GradientDrawable().apply {
            cornerRadius = 12 * d
            setColor(0xFF0069D9.toInt())
        }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun chip(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 12 * d
        setColor(0xFFFFFFFF.toInt())
        setStroke((1 * d).toInt(), 0xFFE2E8F1.toInt())
    }

    private fun card(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 14 * d
        setColor(0xFFFFFFFF.toInt())
        setStroke((1 * d).toInt(), 0xFFE9EEF5.toInt())
    }

    private fun activeCard(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 14 * d
        setColor(0xFFF2F8FF.toInt())
        setStroke((1.4 * d).toInt(), 0xFF9CC4F0.toInt())
    }

    private fun activeChip(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 9 * d
        setColor(0xFFEAF4FF.toInt())
    }
}
