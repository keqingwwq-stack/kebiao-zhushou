package com.dsh.timetable

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Calendar
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

class GridPainter(private val density: Float) {

    /** 绘制时顺手记下每张卡片的位置，供点击命中测试用 */
    val hits = ArrayList<RectF>()
    val hitCourses = ArrayList<Course>()

    private val pWhite = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pName = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pRoom = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pDay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pDate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pRail = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCard = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pToday = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pNow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTri = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pHi = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pPress = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pEmpty = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pEmptySub = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 按下的那张卡片，用来画一层白色高亮（按压反馈） */
    var pressedCourse: Course? = null

    private val accent = Color.parseColor("#0069D9")
    private val lineColor = Color.parseColor("#F0F3F8")

    private val dayNames = arrayOf("一", "二", "三", "四", "五", "六", "日")

    init {
        pWhite.color = Color.WHITE
        pName.color = Color.WHITE
        pName.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        pRoom.color = Color.argb(215, 255, 255, 255)
        pDay.color = Color.parseColor("#8A94A6")
        pRail.color = Color.parseColor("#9AA4B2")
        pRail.textAlign = Paint.Align.CENTER
        pLine.color = lineColor
        pLine.strokeWidth = d(1f)
        pCard.style = Paint.Style.FILL
        pToday.style = Paint.Style.FILL
        pNow.color = Color.argb(150, 0, 105, 217)
        pNow.strokeWidth = d(1.6f)
        pTri.style = Paint.Style.FILL
        pTri.color = accent
        pHi.color = Color.argb(40, 255, 255, 255)
        pPress.color = Color.argb(48, 255, 255, 255)
        pEmpty.color = Color.parseColor("#86909C")
        pEmpty.textAlign = Paint.Align.CENTER
        pEmptySub.color = Color.parseColor("#A9B2BF")
        pEmptySub.textAlign = Paint.Align.CENTER
    }

    private fun d(v: Float): Float = v * density

    /** 把颜色压暗：factor > 1 会变暗（用于卡片底部渐变的收边） */
    private fun darken(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        t: Timetable,
        week: Int,
        todayIndex: Int,
        nowRatio: Float,
        showRail: Boolean
    ) {
        val periods = t.periods
        hits.clear()
        hitCourses.clear()
        if (periods.isEmpty() || width <= 0 || height <= 0) return
        val rows = periods.size
        val railW = if (showRail) d(30f) else 0f
        val headH = d(32f)
        val gridW = width - railW
        val colW = gridW / 7f
        val rowH = (height - headH) / rows.toFloat()

        pDay.textSize = d(10.5f)
        pDate.textSize = d(8f)
        pRail.textSize = d(9f)
        pName.textSize = d(9.5f)
        pRoom.textSize = d(8f)

        if (todayIndex >= 0 && todayIndex < 7) {
            val left = railW + todayIndex * colW
            val shader = LinearGradient(
                0f, headH, 0f, height.toFloat(),
                intArrayOf(Color.parseColor("#F3F9FF"), Color.parseColor("#DCEBFF"), Color.parseColor("#F3F9FF")),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            pToday.shader = shader
            pToday.alpha = 130
            canvas.drawRect(left, headH, left + colW, height.toFloat(), pToday)
            pToday.shader = null
            pToday.alpha = 255
        }

        val weekDates = ArrayList<String>(7)
        run {
            val ws = Weeks.parseDate(t.termStart)
            if (ws != null) {
                val c = ws.clone() as Calendar
                // 对齐到「第 1 周的周一」：教务给的开学日可能不是周一
                val dow = c.get(Calendar.DAY_OF_WEEK)            // 1=周日 ... 7=周六
                c.add(Calendar.DAY_OF_MONTH, -((dow + 5) % 7))
                c.add(Calendar.DAY_OF_MONTH, 7 * (week - 1))
                for (k in 0 until 7) {
                    weekDates.add(c.get(Calendar.DAY_OF_MONTH).toString())
                    c.add(Calendar.DAY_OF_MONTH, 1)
                }
            }
        }

        val pc = Paint.ANTI_ALIAS_FLAG
        for (i in 0 until 7) {
            val cx = railW + i * colW + colW / 2f
            val isToday = i == todayIndex
            if (isToday) {
                val pill = Paint(pc)
                pill.shader = LinearGradient(
                    cx - d(13f), headH / 2f - d(8f), cx + d(13f), headH / 2f + d(8f),
                    Color.parseColor("#2E8BE6"), accent, Shader.TileMode.CLAMP
                )
                val r = RectF(cx - d(13f), headH / 2f - d(9f), cx + d(13f), headH / 2f + d(9f))
                canvas.drawRoundRect(r, d(9f), d(9f), pill)
            }
            pDay.color = if (isToday) Color.WHITE else Color.parseColor("#8A94A6")
            pDay.typeface = if (isToday) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            pDay.textAlign = Paint.Align.CENTER
            val baseline = headH / 2f - d(4f) - (pDay.descent() + pDay.ascent()) / 2f
            canvas.drawText(dayNames[i], cx, baseline, pDay)
            // 星期下面加「几号」
            val dateText = weekDates.getOrNull(i)
            if (dateText != null) {
                pDate.color = if (isToday) Color.argb(230, 255, 255, 255) else Color.parseColor("#B4BDC9")
                pDate.typeface = if (isToday) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
                pDate.textAlign = Paint.Align.CENTER
                val base2 = headH / 2f + d(8f) - (pDate.descent() + pDate.ascent()) / 2f
                canvas.drawText(dateText, cx, base2, pDate)
            }
        }

        for (r in 1 until rows) {
            val y = headH + r * rowH
            canvas.drawLine(railW, y, width.toFloat(), y, pLine)
        }

        if (showRail) {
            for (r in 0 until rows) {
                val cy = headH + r * rowH + rowH / 2f
                // 节次号 + 上下课时间（时间跟着导入的 periods 走）
                pRail.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                pRail.textSize = d(9.5f)
                pRail.color = Color.parseColor("#7B8698")
                canvas.drawText((r + 1).toString(), railW / 2f, cy - d(7.5f), pRail)

                pRail.typeface = Typeface.DEFAULT
                pRail.textSize = d(7f)
                pRail.color = Color.parseColor("#5B6678")
                canvas.drawText(periods[r].start, railW / 2f, cy + d(2f), pRail)

                pRail.textSize = d(6.5f)
                pRail.color = Color.parseColor("#A8B2C1")
                canvas.drawText(periods[r].end, railW / 2f, cy + d(10f), pRail)
            }
        }

        val cardPad = d(1.5f)
        val inner = d(4f)
        for (day in 0 until 7) {
            val left = railW + day * colW
            for (p in planDay(t, day, week, rows, colW)) {
                val c = p.course
                val top = headH + (p.start - 1) * rowH
                val bottom = headH + p.end * rowH
                val slotL = left + p.lane * p.slotW
                val rect = RectF(slotL + cardPad, top + cardPad, slotL + p.slotW - cardPad, bottom - cardPad)
                if (rect.height() < d(10f)) continue
                hits.add(RectF(rect))
                hitCourses.add(c)
                val base = Defaults.color(c.color)
                pCard.shader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.bottom,
                    base, darken(base, 0.86f), Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(rect, d(8f), d(8f), pCard)
                pCard.shader = null
                if (rect.height() > d(26f)) {
                    val hl = RectF(rect.left + d(2f), rect.top + d(1.5f), rect.right - d(2f), rect.top + d(2.5f))
                    canvas.drawRoundRect(hl, d(1f), d(1f), pHi)
                }
                if (pressedCourse != null && pressedCourse === c) {
                    canvas.drawRoundRect(rect, d(8f), d(8f), pPress)
                }

                val textW = rect.width() - inner * 2
                if (textW <= d(8f)) continue

                // 冲突课（一格并排多门）→ 名字竖着写
                if (p.slotW < colW * 0.82f) {
                    drawNameVertical(canvas, c, rect, inner)
                    continue
                }
                val hasRoom = c.room.isNotEmpty()
                val showRoom = hasRoom && rect.height() >= d(30f)

                // 字号自适应：优先做到 2 行内排完（字号尽量大），实在不行才用 3 行
                var nameLayout: StaticLayout? = null
                var fallback: StaticLayout? = null
                for (cand in floatArrayOf(12f, 11.5f, 11f, 10.5f, 10f, 9.5f, 9f, 8.5f, 8f, 7.5f)) {
                    pName.textSize = d(cand)
                    val lay = layoutOf(c.name, pName, textW.toInt(), 3)
                    val cut = lay.getEllipsisCount(lay.lineCount - 1)
                    if (lay.lineCount <= 2 && cut == 0) { nameLayout = lay; break }
                    if (lay.lineCount <= 3 && cut == 0 && fallback == null) fallback = lay
                }
                if (nameLayout == null) nameLayout = fallback
                val name = nameLayout ?: continue
                var roomLayout: StaticLayout? = null
                if (showRoom) {
                    pRoom.textSize = d(8.5f)
                    roomLayout = layoutOf(c.room, pRoom, textW.toInt(), 1)
                }

                val gap = d(1.5f)
                val totalH = name.height.toFloat() + (roomLayout?.height?.toFloat() ?: 0f) +
                    (if (roomLayout != null) gap else 0f)
                var ty = rect.centerY() - totalH / 2f
                val tx = rect.left + inner
                if (roomLayout != null) {
                    canvas.save()
                    canvas.translate(tx, ty)
                    roomLayout.draw(canvas)
                    canvas.restore()
                    ty += roomLayout.height + gap
                }
                canvas.save()
                canvas.translate(tx, ty)
                name.draw(canvas)
                canvas.restore()
            }
        }

        // 这一周完全没有课 → 空状态
        if (hits.isEmpty()) {
            pEmpty.textSize = d(13f)
            pEmptySub.textSize = d(10.5f)
            val cy = headH + (height - headH) / 2f
            canvas.drawText("这一周没有课", width / 2f, cy - d(8f), pEmpty)
            canvas.drawText("点右上角「+」添加，或换一周看看", width / 2f, cy + d(14f), pEmptySub)
        }

        if (nowRatio >= 0f && nowRatio <= rows.toFloat()) {
            val y = headH + nowRatio * rowH
            if (y >= headH && y <= height) {
                canvas.drawLine(railW, y, width.toFloat(), y, pNow)
                val tri = Path()
                val tx = railW
                val th = d(4.5f)
                tri.moveTo(tx - th * 1.6f, y - th)
                tri.lineTo(tx, y)
                tri.lineTo(tx - th * 1.6f, y + th)
                tri.close()
                canvas.drawPath(tri, pTri)
            }
        }
    }

    /**
     * 冲突课时卡片会被压窄（同一格并排两门），横排名字挤成竖条不好看，
     * 这里改成**一个字一行**竖着写：字号按卡的可用高度反算，放不下就截断加省略号。
     */
    private fun drawNameVertical(canvas: Canvas, c: Course, rect: RectF, inner: Float) {
        val chars = c.name.toCharArray()
        val availH = rect.height() - inner * 2f
        if (availH <= 0f) return
        var size = d(10.5f)
        val maxChars = Math.max(1, (availH / (size * 1.06f)).toInt())
        if (chars.size > maxChars) {
            // 字太多：字号还能再小一点就缩，缩不动再截断
            val shrunk = availH / (chars.size * 1.06f)
            if (shrunk >= d(6.5f)) size = shrunk else size = d(6.5f)
        }
        val lineH = size * 1.06f
        val fit = Math.max(1, (availH / lineH).toInt())
        val n = Math.min(chars.size, fit)
        val truncated = n < chars.size
        pName.textSize = size
        pName.color = 0xFFFFFFFF.toInt()
        pName.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val used = (if (truncated) n else n) * lineH
        var y = rect.centerY() - used / 2f + size
        for (i in 0 until n) {
            val ch = if (truncated && i == n - 1) '\u2026' else chars[i]
            val w = pName.measureText(ch.toString())
            canvas.drawText(ch.toString(), rect.centerX() - w / 2f, y, pName)
            y += lineH
        }
    }

    private class Slot(val course: Course, val start: Int, val end: Int, val lane: Int, val slotW: Float)

    /**
     * 把某一天的课按节次排好，时间重叠的课分组成「簇」，簇内再分通道并排画。
     * 这样两门课撞在同一时段时会各占一半宽度，而不是互相盖住。
     */
    private fun planDay(t: Timetable, day: Int, week: Int, rows: Int, colW: Float): List<Slot> {
        val list = ArrayList<Course>()
        for (c in t.courses) {
            if (c.day != day) continue
            if (!Weeks.inWeek(c.weeks, week)) continue
            val s0 = if (c.start < 1) 1 else c.start
            if (s0 > rows) continue
            list.add(c)
        }
        if (list.isEmpty()) return emptyList()
        list.sortWith(compareBy({ it.start }, { it.end }, { it.name }))
        val out = ArrayList<Slot>()
        var clusterEnd = -1
        var lanes = ArrayList<Int>()
        var pending = ArrayList<Slot>()
        fun flush() {
            val cnt = lanes.size
            if (cnt <= 1) {
                for (p in pending) out.add(Slot(p.course, p.start, p.end, 0, colW))
            } else {
                for (p in pending) out.add(Slot(p.course, p.start, p.end, p.lane, colW / cnt))
            }
            pending = ArrayList()
            lanes = ArrayList()
        }
        for (c in list) {
            val s = if (c.start < 1) 1 else c.start
            val e = if (c.end > rows) rows else maxOf(c.end, s)
            if (clusterEnd >= 0 && s > clusterEnd) flush()
            if (e > clusterEnd) clusterEnd = e
            var lane = -1
            for (i in lanes.indices) {
                if (lanes[i] < s) { lane = i; break }
            }
            if (lane < 0) { lanes.add(e); lane = lanes.size - 1 } else lanes[lane] = e
            pending.add(Slot(c, s, e, lane, colW))
        }
        flush()
        return out
    }

    private fun layoutOf(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout {
        val w = if (width < 1) 1 else width
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()
    }

    private fun wrap(text: String, maxWidth: Float, paint: Paint, maxLines: Int): List<String> {
        val out = mutableListOf<String>()
        if (maxWidth <= 0f) return out
        var cur = StringBuilder()
        for (ch in text) {
            cur.append(ch)
            if (paint.measureText(cur.toString()) > maxWidth) {
                cur.deleteCharAt(cur.length - 1)
                if (cur.isEmpty()) continue
                out.add(cur.toString())
                cur = StringBuilder()
                if (out.size >= maxLines) return out
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty() && out.size < maxLines) out.add(cur.toString())
        return if (out.isEmpty()) listOf("") else out
    }

    fun offsetFor(t: Timetable, minutes: Int): Float {
        val periods = t.periods
        if (periods.isEmpty()) return -1f
        for (i in periods.indices) {
            val s = minutesOf(periods[i].start)
            val e = minutesOf(periods[i].end)
            if (s < 0) continue
            if (minutes in s..e && e > s) {
                return i + (minutes - s).toFloat() / (e - s).toFloat()
            }
            if (minutes < s) return i.toFloat()
        }
        return periods.size.toFloat()
    }

    private fun minutesOf(hhmm: String): Int {
        val p = hhmm.split(":")
        if (p.size < 2) return -1
        val h = p[0].toIntOrNull() ?: return -1
        val m = p[1].toIntOrNull() ?: return -1
        return h * 60 + m
    }
}