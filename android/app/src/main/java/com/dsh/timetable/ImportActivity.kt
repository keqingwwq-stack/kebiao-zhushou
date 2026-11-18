package com.dsh.timetable

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.app.Activity

class ImportActivity : Activity() {

    private lateinit var etInput: EditText
    private lateinit var tvPreview: TextView
    private var parsed: List<Course> = emptyList()
    private var data: Timetable = Timetable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)

        etInput = findViewById(R.id.etInput)
        tvPreview = findViewById(R.id.tvPreview)
        data = Store.load(this)

        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                etInput.setText(text)
                Toast.makeText(this, "已粘贴 " + text.length + " 个字符", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "剪贴板是空的", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnParse).setOnClickListener { doParse() }
        findViewById<Button>(R.id.btnApply).setOnClickListener { apply(true) }
        findViewById<Button>(R.id.btnMerge).setOnClickListener { apply(false) }
    }

    private fun doParse() {
        val raw = etInput.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, "先粘贴或输入课表内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (raw.startsWith("{")) {
            val t = JsonCodec.fromJson(raw)
            if (t == null || t.courses.isEmpty()) {
                tvPreview.text = "JSON 解析失败，请检查格式"
                parsed = emptyList()
                return
            }
            data.termStart = t.termStart
            data.totalWeeks = t.totalWeeks
            if (t.periods.isNotEmpty()) data.periods = t.periods
            parsed = t.courses
        } else {
            parsed = Parsers.parse(raw, data)
        }
        if (parsed.isEmpty()) {
            tvPreview.text = "没有解析到课程。\n\n可以试试这些写法：\n周一 1-2节 高等数学 教二-105 张三 1-16周"
            return
        }
        val names = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val sb = StringBuilder()
        sb.append("解析到 ").append(parsed.size).append(" 门课程：\n\n")
        for (c in parsed.sortedWith(compareBy({ it.day }, { it.start }))) {
            sb.append(names[c.day]).append(" 第").append(c.start).append("-").append(c.end).append("节  ")
            sb.append(c.name)
            if (c.room.isNotEmpty()) sb.append(" · ").append(c.room)
            if (c.teacher.isNotEmpty()) sb.append(" · ").append(c.teacher)
            sb.append("  [").append(c.weeks).append("]\n")
        }
        tvPreview.text = sb.toString()
    }

    private fun apply(replace: Boolean) {
        if (parsed.isEmpty()) {
            Toast.makeText(this, "先点『解析预览』", Toast.LENGTH_SHORT).show()
            return
        }
        if (replace) {
            data.courses = parsed.toMutableList()
        } else {
            val keys = data.courses.map { it.day.toString() + "|" + it.start + "|" + it.name }.toMutableSet()
            for (c in parsed) {
                val k = c.day.toString() + "|" + c.start + "|" + c.name
                if (!keys.contains(k)) {
                    data.courses.add(c)
                    keys.add(k)
                }
            }
        }
        Store.save(this, data)
        Toast.makeText(this, "已写入 " + data.courses.size + " 门课程", Toast.LENGTH_SHORT).show()
        finish()
    }
}