package com.dsh.timetable

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimetableDetailActivity : Activity() {

    private val d by lazy { resources.displayMetrics.density }
    private var slotId: String = ""
    private lateinit var etName: EditText
    private lateinit var etUrl: EditText
    private lateinit var etSite: EditText
    private lateinit var spInterval: Spinner
    private lateinit var swAuto: Switch
    private lateinit var autoBox: LinearLayout
    private lateinit var tvSynced: TextView
    private lateinit var btnEnable: Button

    private val intervalLabels = arrayOf("一天", "两天", "三天", "一周", "半个月", "一个月", "半个学期")
    private val intervalDays = intArrayOf(1, 2, 3, 7, 15, 30, 56)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slotId = intent.getStringExtra("id") ?: ""
        val slot = Store.slot(this, slotId)
        if (slot == null) {
            Toast.makeText(this, "这份课表不见了", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFFF2F5FA.toInt())
        val pad = (14 * d).toInt()
        root.setPadding(pad, pad, pad, pad)

        // 顶栏：返回 + 标题 + 保存
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val btnBack = Button(this)
        btnBack.text = "返回"
        btnBack.textSize = 13f
        btnBack.isAllCaps = false
        btnBack.setTextColor(0xFF0069D9.toInt())
        btnBack.background = chip()
        btnBack.setOnClickListener { finish() }
        head.addView(btnBack, LinearLayout.LayoutParams((68 * d).toInt(), (34 * d).toInt()))
        val tvTitle = TextView(this)
        tvTitle.text = "课表详情"
        tvTitle.textSize = 17f
        tvTitle.setTextColor(0xFF1D2433.toInt())
        tvTitle.paint.isFakeBoldText = true
        tvTitle.gravity = Gravity.CENTER
        head.addView(tvTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val btnSave = Button(this)
        btnSave.text = "保存"
        btnSave.textSize = 13f
        btnSave.isAllCaps = false
        btnSave.setTextColor(0xFF0069D9.toInt())
        btnSave.background = chip()
        btnSave.setOnClickListener { save() }
        head.addView(btnSave, LinearLayout.LayoutParams((68 * d).toInt(), (34 * d).toInt()))
        root.addView(head)

        val scroll = ScrollView(this)
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 启用自己的按钮
        btnEnable = Button(this)
        btnEnable.isAllCaps = false
        btnEnable.textSize = 14f
        btnEnable.setTextColor(0xFFFFFFFF.toInt())
        btnEnable.background = GradientDrawable().apply {
            cornerRadius = 12 * d
            setColor(0xFF0069D9.toInt())
        }
        btnEnable.setOnClickListener { enableThis() }
        body.addView(btnEnable, lpTop(LinearLayout.LayoutParams.MATCH_PARENT, (44 * d).toInt(), 12))

        // 名称
        body.addView(label("课表名字"))
        etName = EditText(this)
        etName.setText(slot.name)
        etName.hint = "比如：大二上 / 2026 秋"
        body.addView(etName, lpTop(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 4))

        // 官网链接
        body.addView(label("官网链接"))
        etUrl = EditText(this)
        etUrl.setText(slot.url)
        etUrl.hint = "教务系统课表页地址，如 https://ngjw.nyist.edu.cn/admin/caslogin"
        etUrl.textSize = 12.5f
        body.addView(etUrl, lpTop(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 4))
        if (slot.url.isNotEmpty()) {
            val btnOpen = Button(this)
            btnOpen.text = "现在去这个网址导入一次"
            btnOpen.isAllCaps = false
            btnOpen.textSize = 12.5f
            btnOpen.setTextColor(0xFF0069D9.toInt())
            btnOpen.background = chip()
            btnOpen.setOnClickListener {
                save(quiet = true)
                val i = Intent(this, WebImportActivity::class.java)
                i.putExtra("url", etUrl.text.toString().trim())
                startActivity(i)
            }
            body.addView(btnOpen, lpTop(LinearLayout.LayoutParams.MATCH_PARENT, (38 * d).toInt(), 6))
        }

        // 自动更新开关
        val swRow = LinearLayout(this)
        swRow.orientation = LinearLayout.HORIZONTAL
        swRow.gravity = Gravity.CENTER_VERTICAL
        val swLabel = TextView(this)
        swLabel.text = "自动更新"
        swLabel.textSize = 14f
        swLabel.setTextColor(0xFF1D2433.toInt())
        swRow.addView(swLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        swAuto = Switch(this)
        swAuto.isChecked = slot.auto
        swRow.addView(swAuto)
        body.addView(swRow, lpTop(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 12))

        // 打开开关后才显示的两行
        autoBox = LinearLayout(this)
        autoBox.orientation = LinearLayout.VERTICAL
        body.addView(autoBox, lpTop(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 4))

        autoBox.addView(label("官网名字"))
        etSite = EditText(this)
        etSite.setText(slot.site)
        etSite.hint = "比如：南阳理工学院教务系统"
        autoBox.addView(etSite, lpTop(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 4))

        autoBox.addView(label("更新间隔"))
        spInterval = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, intervalLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spInterval.adapter = adapter
        val idx = intervalDays.indexOf(slot.intervalDays)
        spInterval.setSelection(if (idx >= 0) idx else 3)
        autoBox.addView(spInterval, lpTop(LinearLayout.LayoutParams.MATCH_PARENT, (44 * d).toInt(), 4))

        tvSynced = TextView(this)
        tvSynced.textSize = 11.5f
        tvSynced.setTextColor(0xFF8A94A6.toInt())
        tvSynced.text = if (slot.syncedAt > 0)
            "上次更新：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(slot.syncedAt))
        else "上次更新：还没更新过"
        autoBox.addView(tvSynced, lpTop(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 6))

        // 折叠/展开
        autoBox.visibility = if (slot.auto) View.VISIBLE else View.GONE
        swAuto.setOnCheckedChangeListener { _, checked ->
            autoBox.visibility = if (checked) View.VISIBLE else View.GONE
        }

        refreshEnable(slot)
    }

    override fun onResume() {
        super.onResume()
        if (slotId.isEmpty()) return
        val slot = Store.slot(this, slotId) ?: return
        refreshEnable(slot)
        if (slot.syncedAt > 0) {
            tvSynced.text = "上次更新：" +
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(slot.syncedAt))
        }
    }

    private fun refreshEnable(slot: ShelfSlot) {
        if (!::btnEnable.isInitialized) return
        if (Store.activeId(this) == slot.id) {
            btnEnable.text = "已启用（当前正在用这份）"
            btnEnable.isEnabled = false
            btnEnable.background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(0xFFB8C7D9.toInt())
            }
        } else {
            btnEnable.text = "启用这份课表"
            btnEnable.isEnabled = true
            btnEnable.background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(0xFF0069D9.toInt())
            }
        }
    }

    private fun enableThis() {
        save(quiet = true)
        if (Store.switchTo(this, slotId) != null) {
            Toast.makeText(this, "已启用「" + etName.text.toString().trim() + "」", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            Store.slot(this, slotId)?.let { refreshEnable(it) }
        } else {
            Toast.makeText(this, "这份课表读不出来", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save(quiet: Boolean = false) {
        val name = etName.text.toString().trim().ifEmpty { "课表" }
        val url = etUrl.text.toString().trim()
        val site = etSite.text.toString().trim()
        val auto = swAuto.isChecked
        Store.updateMeta(this, slotId, name, url, auto, site, intervalDays[spInterval.selectedItemPosition])
        if (auto && url.isEmpty()) Toast.makeText(this, "开了自动更新但没填链接", Toast.LENGTH_SHORT).show()
        else if (!quiet) Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        if (!quiet) finish()
    }

    private fun label(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(0xFF8A94A6.toInt())
        return tv
    }

    private fun lpTop(w: Int, h: Int, top: Int): LinearLayout.LayoutParams {
        val lp = if (w < 0) LinearLayout.LayoutParams(w, h) else LinearLayout.LayoutParams(w, h)
        lp.topMargin = (top * d).toInt()
        return lp
    }

    private fun chip(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 12 * d
        setColor(0xFFFFFFFF.toInt())
        setStroke((1 * d).toInt(), 0xFFE2E8F1.toInt())
    }
}
