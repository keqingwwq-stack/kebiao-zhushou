package com.dsh.timetable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Calendar

/**
 * 桌面小组件的位图绘制（RemoteViews 只能用 ImageView，所以整块内容自己画）。
 * 两种样式：today（今日，2×3 竖版）/ week（全周小网格）。
 * 卡片自带圆角底：画布先裁剪成圆角矩形，四角保持透明，老系统上也能显示成圆角。
 */
object WidgetPainter {

    private const val C_TX1 = "#1D2433"
    private const val C_TX2 = "#5B6678"
    private const val C_TX3 = "#8A94A6"
    private const val C_ACCENT = "#0069D9"
    private const val C_LINE = "#E9EEF5"

    private val DAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 圆角白卡底：裁剪 + 白底微渐变 + 1dp 细边框 */
    private fun roundBase(canvas: Canvas, w: Int, h: Int, d: Float) {
        val r = 18f * d
        val inset = 1f * d
        val rect = RectF(inset, inset, w - inset, h - inset)
        val path = Path()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawColor(Color.WHITE)
        val g = Paint(Paint.ANTI_ALIAS_FLAG)
        g.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Color.parseColor("#FFFFFFFF"), Color.parseColor("#FFF7FAFE"), Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, g)
        val b = Paint(Paint.ANTI_ALIAS_FLAG)
        b.style = Paint.Style.STROKE
        b.strokeWidth = 1f * d
        b.color = Color.parseColor(C_LINE)
        canvas.drawRoundRect(rect, r, r, b)
    }

    /** 按可用宽度截断并加省略号 */
    private fun fit(p: Paint, s: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) {
            t = t.substring(0, t.length - 1)
        }
        return if (t.isEmpty()) "" else "$t…"
    }

    private fun textPaint(color: String, sp: Float, d: Float, bold: Boolean = false): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor(color)
        p.textSize = sp * d
        p.isSubpixelText = true
        if (bold) p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        return p
    }

    fun today(ctx: Context, wDp: Int, hDp: Int, nowOverride: Calendar? = null): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceIn(160, 3000)
        val h = (hDp * d).toInt().coerceIn(56, 3000)
        val ratio = h.toFloat() / w
        val wDpF = w / d
        val hDpF = h / d

        // 按形状挑版式：竖长/大块 → 今日列表；竖小框 → 两行迷你；极扁 → 单行；其余 → 卡片
        if (ratio >= 0.55f) {
            if (hDpF >= 118f && wDpF >= 96f) return todayTall(ctx, wDp, hDp, nowOverride)
            return todayMini(ctx, wDp, hDp, nowOverride)
        }
        if (hDpF < 60f) return todayLine(ctx, wDp, hDp, nowOverride)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        roundBase(canvas, w, h, d)

        val t = Store.load(ctx)
        val now = nowOverride ?: Calendar.getInstance()
        val dayIdx = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val cw = Weeks.currentWeek(t)
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val list = t.courses.filter { it.day == dayIdx && Weeks.inWeek(it.weeks, cw) }.sortedBy { it.start }

        var cur: Course? = null
        for (c in list) {
            val s0 = startMinutes(t, c.start)
            val e0 = endMinutes(t, c.end)
            if (s0 < 0 || e0 < 0) continue
            if (mins >= s0 - 30 && mins <= e0) { cur = c; break }
        }
        var soon: Course? = null
        if (cur == null) {
            for (c in list) {
                val s0 = startMinutes(t, c.start)
                if (s0 >= 0 && s0 - 30 > mins) { soon = c; break }
            }
        }
        var later: Course? = null
        var laterDay = ""
        if (cur == null && soon == null) {
            for (k in 1..7) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, k)
                val di = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                val ls = t.courses.filter { it.day == di && Weeks.inWeek(it.weeks, cw) }.sortedBy { it.start }
                if (ls.isNotEmpty()) {
                    later = ls[0]
                    laterDay = if (k == 1) "明天" else DAYS[di]
                    break
                }
            }
        }

        val show = cur ?: soon
        var hint = ""
        if (show != null) {
            val s0 = startMinutes(t, show.start)
            val e0 = endMinutes(t, show.end)
            if (cur != null) {
                if (mins < s0) hint = "还有 ${s0 - mins} 分钟上课"
                else if (mins >= e0 - 30) {
                    val nx = list.firstOrNull { startMinutes(t, it.start) > s0 }
                    if (nx != null) hint = "下一节 ${startText(t, nx.start)} ${nx.name}"
                }
            } else {
                hint = "还有 ${s0 - mins} 分钟上课"
            }
        } else if (later != null) {
            hint = "${laterDay} ${startText(t, later.start)} ${later.name}"
        }

        // 基准 167x67dp，按短边等比缩放；放不下就依次丢「提示 → 教室 → 时间 → 再缩一点」
        var sc = minOf(w / (167f * d), h / (67f * d)).coerceIn(0.66f, 3.0f)
        var withTime = true
        var withRoom = (show?.room ?: "").isNotEmpty()
        var withHint = hint.isNotEmpty()

        fun rowHeights(scale: Float): List<Float> {
            val pT = textPaint(C_ACCENT, 14f * scale, d, true)
            val pN = textPaint(C_TX1, 14f * scale, d, true)
            val pR = textPaint(C_TX2, 9.5f * scale, d)
            val pI = textPaint("#A8B2C1", 9f * scale, d)
            fun hgt(p: Paint, k: Float): Float {
                val fm = p.fontMetrics
                return (fm.descent - fm.ascent) * k
            }
            val out = ArrayList<Float>()
            if (withTime) out.add(hgt(pT, 1.25f))
            out.add(hgt(pN, 1.15f))
            if (withRoom) out.add(hgt(pR, 1.20f))
            if (withHint) out.add(hgt(pI, 1.25f))
            return out
        }

        var parts = rowHeights(sc)
        var avail = h - 10f * d * sc
        var guard = 0
        while (parts.sum() > avail && guard < 8) {
            if (withHint) withHint = false
            else if (withRoom) withRoom = false
            else if (withTime) withTime = false
            else sc *= 0.9f
            parts = rowHeights(sc)
            avail = h - 10f * d * sc
            guard++
        }

        val total = parts.sum()
        var y = (h - total) / 2f

        val padX = 13f * d * sc
        val padR = w - padX
        val barW = 3.4f * d * sc
        val tx = padX + barW + 9f * d * sc

        val pTime = textPaint(C_ACCENT, 14f * sc, d, true)
        val pPer = textPaint(C_TX3, 9f * sc, d)
        val pName = textPaint(C_TX1, 14f * sc, d, true)
        val pRoom = textPaint(C_TX2, 9.5f * sc, d)
        val pHint = textPaint("#A8B2C1", 9f * sc, d)

        val pBar = Paint(Paint.ANTI_ALIAS_FLAG)
        pBar.color = if (show != null) Defaults.color(show.color) else Color.parseColor("#C7DDF7")
        canvas.drawRoundRect(RectF(padX, y + 2f * d, padX + barW, y + total - 2f * d),
            barW / 2f, barW / 2f, pBar)

        var idx = 0
        if (withTime && show != null) {
            val base = y - pTime.fontMetrics.ascent
            val st = startText(t, show.start)
            canvas.drawText(st, tx, base, pTime)
            val perLabel = "第 ${show.start}-${show.end} 节"
            val perX = tx + pTime.measureText(st) + 8f * d * sc
            val wkLabel = "第 $cw 周"
            val pW = textPaint(C_TX3, 8.5f * sc, d)
            val wkW = pW.measureText(wkLabel)
            val perW = pPer.measureText(perLabel)
            if (perX + perW + 8f * d * sc + wkW <= padR) {
                canvas.drawText(perLabel, perX, base, pPer)
                pW.textAlign = Paint.Align.RIGHT
                canvas.drawText(wkLabel, padR, base, pW)
            } else {
                canvas.drawText(fit(pPer, perLabel, padR - perX), perX, base, pPer)
            }
            y += parts[idx]; idx++
        }

        if (show != null) {
            canvas.drawText(fit(pName, show.name, padR - tx), tx, y - pName.fontMetrics.ascent, pName)
            y += parts[idx]; idx++
            if (withRoom) {
                canvas.drawText(fit(pRoom, show.room, padR - tx), tx, y - pRoom.fontMetrics.ascent, pRoom)
                y += parts[idx]; idx++
            }
            if (withHint) {
                canvas.drawText(fit(pHint, hint, padR - tx), tx, y - pHint.fontMetrics.ascent, pHint)
            }
        } else {
            val pN = textPaint(C_TX1, 13.5f * sc, d, true)
            canvas.drawText(noClassText(), tx, y - pN.fontMetrics.ascent, pN)
            y += parts[idx]; idx++
            if (withHint) {
                canvas.drawText(fit(pHint, hint, padR - tx), tx, y - pHint.fontMetrics.ascent, pHint)
            }
        }
        return bmp
    }

    /** 竖长/大块：表头（周次·星期 + 时钟）+ 今日课程列表 */
    fun todayTall(ctx: Context, wDp: Int, hDp: Int, nowOverride: Calendar? = null): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceIn(160, 3000)
        val h = (hDp * d).toInt().coerceIn(70, 3000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        roundBase(canvas, w, h, d)

        val t = Store.load(ctx)
        val now = nowOverride ?: Calendar.getInstance()
        val dayIdx = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val cw = Weeks.currentWeek(t)
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val list = t.courses.filter { it.day == dayIdx && Weeks.inWeek(it.weeks, cw) }.sortedBy { it.start }

        var cur: Course? = null
        for (c in list) {
            val s0 = startMinutes(t, c.start)
            val e0 = endMinutes(t, c.end)
            if (s0 < 0 || e0 < 0) continue
            if (mins >= s0 - 30 && mins <= e0) { cur = c; break }
        }
        var hint = ""
        if (cur != null) {
            val s0 = startMinutes(t, cur.start)
            val e0 = endMinutes(t, cur.end)
            if (mins >= e0 - 30) {
                val nx = list.firstOrNull { startMinutes(t, it.start) > s0 }
                if (nx != null) hint = "下一节 ${startText(t, nx.start)} ${nx.name}"
            }
        } else if (list.isNotEmpty()) {
            val soon = list.firstOrNull { startMinutes(t, it.start) - 30 > mins }
            if (soon != null) hint = "还有 ${startMinutes(t, soon.start) - mins} 分钟上课"
        }

        // 基准 110x180dp，按短边缩放（只按宽度会让又宽又高的大块字号爆掉）
        val esc = minOf(w / (110f * d), h / (180f * d)).coerceIn(0.80f, 2.2f)
        val padX = 14f * d * esc
        val padR = w - padX

        val pWeek = textPaint(C_TX3, 10f * esc, d)
        val pClock = textPaint(C_ACCENT, 15f * esc, d, true)
        val headBase = 20f * d * esc - pWeek.fontMetrics.ascent
        canvas.drawText(fit(pWeek, weekHead(cw, dayIdx), padR - padX - 70f * d * esc), padX, headBase, pWeek)
        pClock.textAlign = Paint.Align.RIGHT
        canvas.drawText(clockText(now), padR, 21f * d * esc - pClock.fontMetrics.ascent, pClock)

        val pLine = Paint(Paint.ANTI_ALIAS_FLAG)
        pLine.color = Color.parseColor(C_LINE)
        pLine.strokeWidth = 1f * d
        val lineY = 34f * d * esc
        canvas.drawLine(padX, lineY, padR, lineY, pLine)

        val footH = if (h > 118f * d * esc) 22f * d * esc else 0f
        val top = lineY + 8f * d * esc
        val bottom = h - footH - 6f * d
        val avail = bottom - top

        if (list.isEmpty()) {
            val pN = textPaint(C_TX1, 12f * esc, d, true)
            canvas.drawText(noClassText(), padX, top + (pN.fontMetrics.descent - pN.fontMetrics.ascent),
                pN)
        } else {
            val rowMin = 34f * d * esc   // 一行至少 34dp，才放得下「课名 + 时间·教室」两行
            var n = minOf(4, list.size)
            while (n > 1 && avail / n < rowMin) n--
            val items = list.take(n)
            val rowH = avail / items.size
            val nameSp = (rowH / d * 0.30f).coerceIn(8.5f, 15f)
            val subSp = (nameSp * 0.74f).coerceIn(6.5f, 11f)
            val pN = textPaint(C_TX1, nameSp, d, true)
            val pS = textPaint(C_TX2, subSp, d)
            val titleH = (pN.fontMetrics.descent - pN.fontMetrics.ascent) * 1.30f
            val subH = (pS.fontMetrics.descent - pS.fontMetrics.ascent) * 1.30f
            var y = top
            for (c in items) {
                if (cur != null && c === cur) {
                    val bg = Paint(Paint.ANTI_ALIAS_FLAG)
                    bg.color = Color.parseColor("#EAF4FF")
                    canvas.drawRoundRect(padX - 6f * d * esc, y + 1f * d, padR + 6f * d * esc,
                        y + rowH - 2f * d, 7f * d * esc, 7f * d * esc, bg)
                }
                val pBar = Paint(Paint.ANTI_ALIAS_FLAG)
                pBar.color = Defaults.color(c.color)
                canvas.drawRoundRect(padX, y + 5f * d, padX + 3.2f * d * esc, y + rowH - 5f * d,
                    2f * d, 2f * d, pBar)
                val tx = padX + 9f * d * esc
                // 整块垂直居中：标题 + 副行，超了就只画标题
                val wantSub = (titleH + subH) <= rowH - 2f * d
                val blockH = if (wantSub) titleH + subH else titleH
                val bTop = y + (rowH - blockH) / 2f
                canvas.drawText(fit(pN, c.name, padR - tx), tx, bTop - pN.fontMetrics.ascent, pN)
                if (wantSub) {
                    val sub = rangeText(t, c) + (if (c.room.isNotEmpty()) " · " + c.room else "")
                    canvas.drawText(fit(pS, sub, padR - tx), tx, bTop + titleH - pS.fontMetrics.ascent, pS)
                }
                y += rowH
            }
        }

        if (footH > 0f && hint.isNotEmpty()) {
            val pH = textPaint("#A8B2C1", 9.5f * esc, d)
            canvas.drawText(fit(pH, hint, padR - padX), padX, h - 9f * d, pH)
        }
        return bmp
    }

    /** "第 11 周 · 周二" */
    private fun weekHead(week: Int, dayIdx: Int): String = "第 $week 周" + " · " + DAYS[dayIdx]

    /** "10:10 - 11:40" */
    private fun rangeText(t: Timetable, c: Course): String {
        val a = startText(t, c.start)
        val b = t.periods.getOrNull(c.end - 1)?.end ?: ""
        return if (a.isEmpty()) "" else "$a - $b"
    }

    private fun clockText(now: Calendar): String =
        String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))

    private fun noClassText(): String = "今天没有课"

    fun week(ctx: Context, wDp: Int, hDp: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceIn(240, 2000)
        val h = (hDp * d).toInt().coerceIn(180, 2000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        roundBase(canvas, w, h, d)

        val t = Store.load(ctx)
        val now = Calendar.getInstance()
        val dayIdx = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val cw = Weeks.currentWeek(t)

        val pH = textPaint(C_TX3, 9.5f, d)
        canvas.drawText(fit(pH, "第 $cw 周 · ${DAYS[dayIdx]}", w - 20f * d), 10f * d, 16f * d, pH)

        val painter = GridPainter(d)
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val offset = painter.offsetFor(t, mins)
        canvas.save()
        canvas.translate(0f, 20f * d)
        painter.draw(canvas, w, h - (24 * d).toInt(), t, cw, dayIdx, offset, false)
        canvas.restore()
        return bmp
    }

    private fun isNow(t: Timetable, c: Course, mins: Int): Boolean {
        val s = startMinutes(t, c.start)
        val e = endMinutes(t, c.end)
        return s >= 0 && e >= 0 && mins in s..e
    }

    private fun startText(t: Timetable, no: Int): String = t.periods.getOrNull(no - 1)?.start ?: ""

    private fun startMinutes(t: Timetable, no: Int): Int = minutes(t.periods.getOrNull(no - 1)?.start)

    private fun endMinutes(t: Timetable, no: Int): Int = minutes(t.periods.getOrNull(no - 1)?.end)

    private fun minutes(text: String?): Int {
        if (text == null) return -1
        val p = text.split(":")
        if (p.size < 2) return -1
        val h = p[0].toIntOrNull() ?: return -1
        val m = p[1].toIntOrNull() ?: return -1
        return h * 60 + m
    }


    /** 极扁的条（比如 6x1）：一行放下 时间 + 课名 + 教室 */
    private fun todayLine(ctx: Context, wDp: Int, hDp: Int, nowOverride: Calendar?): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceIn(160, 3000)
        val h = (hDp * d).toInt().coerceIn(56, 3000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        roundBase(canvas, w, h, d)
        val info = pickNow(ctx, nowOverride)
        val sc = minOf(w / (200f * d), h / (52f * d)).coerceIn(0.62f, 2.4f)
        val padX = 12f * d * sc
        val barW = 3.2f * d * sc
        canvas.drawRoundRect(RectF(padX, 11f * d * sc, padX + barW, h - 11f * d * sc),
            barW / 2f, barW / 2f, solid(info.color))
        var x = padX + barW + 9f * d * sc
        val cy = h / 2f
        val pTime = textPaint(C_ACCENT, 13f * sc, d, true)
        val pName = textPaint(C_TX1, 13f * sc, d, true)
        val pRoom = textPaint(C_TX2, 9f * sc, d)
        if (info.time.isNotEmpty()) {
            canvas.drawText(info.time, x, baseline(cy, pTime), pTime)
            x += pTime.measureText(info.time) + 9f * d * sc
        }
        val roomRight = w - padX
        val nameText = fit(pName, info.name, roomRight - x)
        canvas.drawText(nameText, x, baseline(cy, pName), pName)
        var used = x + pName.measureText(nameText) + 9f * d * sc
        if (info.room.isNotEmpty() && used < roomRight - 40f * d) {
            canvas.drawText(fit(pRoom, info.room, roomRight - used), used, baseline(cy, pRoom), pRoom)
        }
        return bmp
    }

    /** 竖着的小框（比如 2x2 竖、1x2 竖）：两行，课名 + 时间/教室，不画表头 */
    private fun todayMini(ctx: Context, wDp: Int, hDp: Int, nowOverride: Calendar?): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceIn(160, 3000)
        val h = (hDp * d).toInt().coerceIn(70, 3000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        roundBase(canvas, w, h, d)
        val info = pickNow(ctx, nowOverride)
        val sc = minOf(w / (110f * d), h / (150f * d)).coerceIn(0.70f, 2.2f)
        val padX = 12f * d * sc
        val barW = 3.2f * d * sc
        val pName = textPaint(C_TX1, 13f * sc, d, true)
        val pSub = textPaint(C_TX3, 9.5f * sc, d)
        val hName = (pName.fontMetrics.descent - pName.fontMetrics.ascent) * 1.15f
        val hSub = (pSub.fontMetrics.descent - pSub.fontMetrics.ascent) * 1.2f
        val total = hName + hSub
        val top = maxOf(10f * d * sc, (h - total) / 2f)
        canvas.drawRoundRect(RectF(padX, top + 2f * d * sc, padX + barW, top + total - 2f * d * sc),
            barW / 2f, barW / 2f, solid(info.color))
        val tx = padX + barW + 9f * d * sc
        val right = w - padX
        canvas.drawText(fit(pName, info.name, right - tx), tx, top - pName.fontMetrics.ascent, pName)
        val sub = listOf(info.time, info.room).filter { it.isNotEmpty() }.joinToString(" · ")
        if (sub.isNotEmpty()) {
            canvas.drawText(fit(pSub, sub, right - tx), tx, top + hName - pSub.fontMetrics.ascent, pSub)
        }
        return bmp
    }

    private class NowInfo(val name: String, val time: String, val room: String, val color: Int)

    /** 给极简版式用的「当前/下一节/今天没课」取值 */
    private fun pickNow(ctx: Context, nowOverride: Calendar?): NowInfo {
        val t = Store.load(ctx)
        val now = nowOverride ?: Calendar.getInstance()
        val dayIdx = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val cw = Weeks.currentWeek(t)
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val list = t.courses.filter { it.day == dayIdx && Weeks.inWeek(it.weeks, cw) }.sortedBy { it.start }
        var sel: Course? = null
        for (c in list) {
            val st = startMinutes(t, c.start)
            val en = endMinutes(t, c.end)
            if (st >= 0 && en >= 0 && mins >= st - 30 && mins <= en) { sel = c; break }
        }
        if (sel == null) {
            for (c in list) {
                val st = startMinutes(t, c.start)
                if (st >= 0 && st - 30 > mins) { sel = c; break }
            }
        }
        if (sel == null) {
            for (k in 1..7) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_MONTH, k)
                val di = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                val ls = t.courses.filter { it.day == di && Weeks.inWeek(it.weeks, cw) }.sortedBy { it.start }
                if (ls.isNotEmpty()) {
                    val c = ls[0]
                    val dd = if (k == 1) "明天" else DAYS[di]
                    return NowInfo(c.name, "$dd ${startText(t, c.start)}", c.room, Defaults.color(c.color))
                }
            }
            return NowInfo("今天没有课", "", "", Color.parseColor("#C7DDF7"))
        }
        val c = sel
        return NowInfo(c.name, startText(t, c.start), c.room, Defaults.color(c.color))
    }

    private fun baseline(cy: Float, p: Paint): Float {
        val fm = p.fontMetrics
        return cy - (fm.ascent + fm.descent) / 2f
    }

    private fun solid(color: Int): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        return p
    }

}
