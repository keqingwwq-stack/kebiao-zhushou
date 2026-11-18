package com.dsh.timetable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class Period(val no: Int, val start: String, val end: String)

data class Course(
    val name: String,
    val room: String,
    val teacher: String,
    val day: Int,
    val start: Int,
    val end: Int,
    val weeks: String,
    val color: Int
) {
    val span: Int get() = if (end >= start) end - start + 1 else 1
}

data class Timetable(
    var termStart: String = "2026-09-01",
    var totalWeeks: Int = 16,
    var periods: MutableList<Period> = Defaults.periods(),
    var courses: MutableList<Course> = mutableListOf()
)

object Defaults {
    val COLORS = intArrayOf(
        0xFF73B4EF.toInt(),
        0xFF7ACFA6.toInt(),
        0xFF9F8BEA.toInt(),
        0xFFE286AB.toInt(),
        0xFFFFCB6D.toInt(),
        0xFF8DC63F.toInt(),
        0xFF1CBBB4.toInt(),
        0xFFF37B1D.toInt()
    )

    fun color(index: Int): Int {
        val i = ((index % COLORS.size) + COLORS.size) % COLORS.size
        return COLORS[i]
    }

    fun periods(): MutableList<Period> {
        val raw = arrayOf(
            "08:00", "08:45", "08:55", "09:40",
            "10:00", "10:45", "10:55", "11:40",
            "14:00", "14:45", "14:55", "15:40",
            "16:00", "16:45", "16:55", "17:40",
            "19:00", "19:45", "19:55", "20:40",
            "20:50", "21:35", "21:45", "22:30"
        )
        val list = mutableListOf<Period>()
        var i = 0
        while (i + 1 < raw.size) {
            list.add(Period(list.size + 1, raw[i], raw[i + 1]))
            i += 2
        }
        return list
    }

    fun demo(): MutableList<Course> = mutableListOf(
        Course("高等数学A(一)", "教二-201", "张三", 0, 1, 2, "1-16", 0),
        Course("大学英语", "外语楼302", "王五", 0, 3, 4, "1-8", 1),
        Course("电路分析基础", "教二-105", "赵六", 1, 3, 4, "1-16", 2),
        Course("大学物理", "教二-210", "吴九", 1, 5, 6, "1-16", 3),
        Course("数据结构", "计算机楼B203", "郑十", 2, 5, 6, "1-16", 4),
        Course("体育(篮球)", "体育馆", "", 2, 7, 8, "1-16", 5),
        Course("模拟电子技术", "教二-301", "王五", 2, 9, 10, "3-16", 6),
        Course("数字电子技术", "教三-305", "李四", 3, 1, 2, "1-16", 7),
        Course("概率论与数理统计", "教一-108", "孙七", 3, 3, 4, "1-16", 0),
        Course("程序设计实验", "机房A402", "周八", 4, 3, 4, "2-16:even", 1),
        Course("马克思主义基本原理", "教四-201", "钱一", 4, 5, 6, "1-8,10-16", 2)
    )
}

object JsonCodec {
    fun toJson(t: Timetable): String {
        val root = JSONObject()
        root.put("termStart", t.termStart)
        root.put("totalWeeks", t.totalWeeks)
        val pa = JSONArray()
        for (p in t.periods) {
            val po = JSONObject()
            po.put("no", p.no)
            po.put("start", p.start)
            po.put("end", p.end)
            pa.put(po)
        }
        root.put("periods", pa)
        val ca = JSONArray()
        for (c in t.courses) {
            val co = JSONObject()
            co.put("name", c.name)
            co.put("room", c.room)
            co.put("teacher", c.teacher)
            co.put("day", c.day)
            co.put("start", c.start)
            co.put("end", c.end)
            co.put("weeks", c.weeks)
            co.put("color", c.color)
            ca.put(co)
        }
        root.put("courses", ca)
        return root.toString()
    }

    fun fromJson(text: String): Timetable? {
        return try {
            val root = JSONObject(text)
            val t = Timetable()
            t.termStart = root.optString("termStart", t.termStart)
            t.totalWeeks = root.optInt("totalWeeks", 16)
            val pa = root.optJSONArray("periods")
            if (pa != null && pa.length() > 0) {
                val list = mutableListOf<Period>()
                for (i in 0 until pa.length()) {
                    val po = pa.getJSONObject(i)
                    list.add(Period(po.optInt("no", i + 1), po.optString("start", ""), po.optString("end", "")))
                }
                t.periods = list
            }
            val ca = root.optJSONArray("courses")
            if (ca != null) {
                val list = mutableListOf<Course>()
                for (i in 0 until ca.length()) {
                    val co = ca.getJSONObject(i)
                    val s = co.optInt("start", 1)
                    list.add(
                        Course(
                            co.optString("name", "未命名"),
                            co.optString("room", ""),
                            co.optString("teacher", ""),
                            co.optInt("day", 0),
                            s,
                            co.optInt("end", s),
                            co.optString("weeks", "1-16"),
                            co.optInt("color", i)
                        )
                    )
                }
                t.courses = list
            }
            t
        } catch (e: Exception) {
            null
        }
    }
}

/** 书架上一份课表：id + 名字 + 序列化内容 + 自动更新用的元信息 */
data class ShelfSlot(
    val id: String,
    var name: String,
    var json: String,
    var url: String = "",
    var auto: Boolean = false,
    var site: String = "",
    var intervalDays: Int = 7,
    var syncedAt: Long = 0L
)

object Store {
    private const val PREFS = "timetable"
    private const val KEY = "json"
    private const val KEY_SHELF = "shelf"
    private const val KEY_ACTIVE = "activeId"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读「当前」这份课表；没有书架就用旧数据迁移出一份 */
    fun load(ctx: Context): Timetable {
        val p = sp(ctx)
        val raw = p.getString(KEY, null)
        if (raw != null) {
            val parsed = JsonCodec.fromJson(raw)
            if (parsed != null) {
                ensureShelf(ctx, raw)
                return parsed
            }
        }
        val fresh = Timetable(courses = Defaults.demo())
        save(ctx, fresh)
        return fresh
    }

    /** 写「当前」这份，同时同步到书架对应条目 */
    fun save(ctx: Context, t: Timetable) {
        val j = JsonCodec.toJson(t)
        val p = sp(ctx)
        val id = p.getString(KEY_ACTIVE, null) ?: ""
        val list = shelfOf(p)
        if (list.isEmpty()) {
            list.add(ShelfSlot(newId(), "我的课表", j))
            p.edit().putString(KEY_ACTIVE, list[0].id).apply()
        } else {
            val hit = list.firstOrNull { it.id == id } ?: list[0]
            hit.json = j
        }
        p.edit().putString(KEY, j).putString(KEY_SHELF, shelfJson(list)).apply()
    }

    fun shelf(ctx: Context): List<ShelfSlot> {
        val p = sp(ctx)
        ensureShelf(ctx, p.getString(KEY, null))
        return shelfOf(p)
    }

    fun activeId(ctx: Context): String {
        val p = sp(ctx)
        ensureShelf(ctx, p.getString(KEY, null))
        val id = p.getString(KEY_ACTIVE, null)
        val list = shelfOf(p)
        return if (id != null && list.any { it.id == id }) id else (list.firstOrNull()?.id ?: "")
    }

    fun switchTo(ctx: Context, id: String): Timetable? {
        val p = sp(ctx)
        val list = shelfOf(p)
        val slot = list.firstOrNull { it.id == id } ?: return null
        val t = JsonCodec.fromJson(slot.json) ?: return null
        p.edit().putString(KEY, slot.json).putString(KEY_ACTIVE, id).apply()
        return t
    }

    fun add(ctx: Context, name: String, t: Timetable): String {
        val j = JsonCodec.toJson(t)
        val id = newId()
        val p = sp(ctx)
        val list = shelfOf(p)
        list.add(ShelfSlot(id, name, j))
        p.edit().putString(KEY_SHELF, shelfJson(list)).apply()
        return id
    }

    fun rename(ctx: Context, id: String, name: String) {
        val p = sp(ctx)
        val list = shelfOf(p)
        list.firstOrNull { it.id == id }?.name = name
        p.edit().putString(KEY_SHELF, shelfJson(list)).apply()
    }

    /** 至少保留一份，删光了就把当前这份重新种回去 */
    fun remove(ctx: Context, id: String): Boolean {
        val p = sp(ctx)
        val list = shelfOf(p)
        if (list.size <= 1) return false
        list.removeAll { it.id == id }
        val active = p.getString(KEY_ACTIVE, null)
        val e = p.edit().putString(KEY_SHELF, shelfJson(list))
        if (active == id) {
            val first = list[0]
            e.putString(KEY, first.json).putString(KEY_ACTIVE, first.id)
        }
        e.apply()
        return true
    }

    fun duplicate(ctx: Context, id: String, name: String): String? {
        val p = sp(ctx)
        val list = shelfOf(p)
        val slot = list.firstOrNull { it.id == id } ?: return null
        val nid = newId()
        list.add(ShelfSlot(nid, name, slot.json))
        p.edit().putString(KEY_SHELF, shelfJson(list)).apply()
        return nid
    }

    fun slot(ctx: Context, id: String): ShelfSlot? = shelfOf(sp(ctx)).firstOrNull { it.id == id }

    fun updateMeta(
        ctx: Context,
        id: String,
        name: String,
        url: String,
        auto: Boolean,
        site: String,
        intervalDays: Int
    ) {
        val p = sp(ctx)
        val list = shelfOf(p)
        val slot = list.firstOrNull { it.id == id } ?: return
        slot.name = name
        slot.url = url
        slot.auto = auto
        slot.site = site
        slot.intervalDays = if (intervalDays < 1) 7 else intervalDays
        p.edit().putString(KEY_SHELF, shelfJson(list)).apply()
    }

    fun markSynced(ctx: Context, id: String) {
        val p = sp(ctx)
        val list = shelfOf(p)
        val slot = list.firstOrNull { it.id == id } ?: return
        slot.syncedAt = System.currentTimeMillis()
        p.edit().putString(KEY_SHELF, shelfJson(list)).apply()
    }

    private fun newId(): String = "t" + System.currentTimeMillis().toString(36) + (0..999).random()

    private fun ensureShelf(ctx: Context, raw: String?) {
        val p = sp(ctx)
        if (shelfOf(p).isNotEmpty()) return
        val list = ArrayList<ShelfSlot>()
        val body = raw ?: JsonCodec.toJson(Timetable(courses = Defaults.demo()))
        list.add(ShelfSlot(newId(), "我的课表", body))
        p.edit().putString(KEY, body).putString(KEY_ACTIVE, list[0].id)
            .putString(KEY_SHELF, shelfJson(list)).apply()
    }

    private fun shelfOf(p: android.content.SharedPreferences): MutableList<ShelfSlot> {
        val out = ArrayList<ShelfSlot>()
        val raw = p.getString(KEY_SHELF, null) ?: return out
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                out.add(
                    ShelfSlot(
                        id,
                        o.optString("name", "课表"),
                        o.optString("json", "{}"),
                        o.optString("url", ""),
                        o.optBoolean("auto", false),
                        o.optString("site", ""),
                        o.optInt("intervalDays", 7),
                        o.optLong("syncedAt", 0L)
                    )
                )
            }
        } catch (e: Throwable) {
        }
        return out
    }

    private fun shelfJson(list: List<ShelfSlot>): String {
        val arr = org.json.JSONArray()
        for (s in list) {
            val o = org.json.JSONObject()
            o.put("id", s.id)
            o.put("name", s.name)
            o.put("json", s.json)
            o.put("url", s.url)
            o.put("auto", s.auto)
            o.put("site", s.site)
            o.put("intervalDays", s.intervalDays)
            o.put("syncedAt", s.syncedAt)
            arr.put(o)
        }
        return arr.toString()
    }
}

object Weeks {
    fun parseDate(text: String): Calendar? {
        val x = text.trim().replace("/", "-").replace(".", "-")
        val parts = x.split("-")
        if (parts.size < 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (m < 1 || m > 12 || d < 1 || d > 31) return null
        val c = Calendar.getInstance()
        c.set(y, m - 1, d, 0, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c
    }

    fun parseCompact(text: String?): Calendar? {
        if (text == null) return null
        val x = text.trim().replace("-", "").replace("/", "").replace("Z", "")
        if (x.length < 8) return null
        return parseDate(x.substring(0, 4) + "-" + x.substring(4, 6) + "-" + x.substring(6, 8))
    }

    fun weekOfDay(day: Calendar?, t: Timetable): Int {
        val start = parseDate(t.termStart) ?: return 1
        val target = day ?: return 1
        val a = start.clone() as Calendar
        val b = target.clone() as Calendar
        a.set(Calendar.HOUR_OF_DAY, 0)
        a.set(Calendar.MINUTE, 0)
        a.set(Calendar.SECOND, 0)
        a.set(Calendar.MILLISECOND, 0)
        b.set(Calendar.HOUR_OF_DAY, 0)
        b.set(Calendar.MINUTE, 0)
        b.set(Calendar.SECOND, 0)
        b.set(Calendar.MILLISECOND, 0)
        // 教务给的开学日可能不是周一，先对齐到那一周的周一，否则第几周会差一周
        val dow = a.get(Calendar.DAY_OF_WEEK)
        val days = ((b.timeInMillis - a.timeInMillis) / 86400000L).toInt() - ((dow + 5) % 7)
        val w = days / 7 + 1
        return if (w < 1) 1 else w
    }

    fun currentWeek(t: Timetable): Int = weekOfDay(Calendar.getInstance(), t)

    fun inWeek(spec: String, week: Int): Boolean {
        if (spec.isBlank()) return true
        var s = spec.trim()
        var parity = 0
        if (s.endsWith(":odd")) {
            parity = 1
            s = s.dropLast(4)
        } else if (s.endsWith(":even")) {
            parity = 2
            s = s.dropLast(5)
        }
        val hasOdd = s.contains("单")
        val hasEven = s.contains("双")
        if (hasOdd && !hasEven) parity = 1
        if (hasEven && !hasOdd) parity = 2
        s = s.replace("单", "").replace("双", "").replace("周", "").replace(" ", "")
        var hit = false
        for (part in s.split(",", "，")) {
            if (part.isEmpty()) continue
            val m = Regex("(\\d+)[-~–—](\\d+)").find(part)
            if (m != null) {
                val a = m.groupValues[1].toIntOrNull() ?: continue
                val b = m.groupValues[2].toIntOrNull() ?: continue
                if (week >= a && week <= b) hit = true
            } else {
                val n = part.toIntOrNull() ?: continue
                if (n == week) hit = true
            }
        }
        if (!hit) return false
        return when (parity) {
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
        }
    }

    fun normalize(spec: String, total: Int): String {
        var x = spec.trim()
        var parity = ""
        if (x.contains("单")) parity = ":odd"
        if (x.contains("双")) parity = ":even"
        x = x.replace("单", "").replace("双", "").replace("周", "")
            .replace("(", "").replace(")", "").replace("（", "").replace("）", "")
            .replace("第", "").replace(" ", "")
        val sb = StringBuilder()
        for (part in x.split(",", "，")) {
            if (part.isEmpty()) continue
            val m = Regex("(\\d+)[-~–—](\\d+)").find(part)
            val piece: String = if (m != null) {
                val a = m.groupValues[1]
                val b = m.groupValues[2]
                a + "-" + b
            } else {
                val n = part.toIntOrNull()
                if (n == null) "" else n.toString() + "-" + n.toString()
            }
            if (piece.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(",")
            sb.append(piece)
        }
        val base = if (sb.isEmpty()) "1-" + total else sb.toString()
        return base + parity
    }
}

object Parsers {
    private val dayMap = mapOf(
        "周一" to 0, "星期一" to 0, "礼拜一" to 0, "mon" to 0,
        "周二" to 1, "星期二" to 1, "礼拜二" to 1, "tue" to 1,
        "周三" to 2, "星期三" to 2, "礼拜三" to 2, "wed" to 2,
        "周四" to 3, "星期四" to 3, "礼拜四" to 3, "thu" to 3,
        "周五" to 4, "星期五" to 4, "礼拜五" to 4, "fri" to 4,
        "周六" to 5, "星期六" to 5, "礼拜六" to 5, "sat" to 5,
        "周日" to 6, "周天" to 6, "星期日" to 6, "星期天" to 6, "sun" to 6
    )

    fun dayOf(text: String): Int {
        val s = text.trim().lowercase(Locale.US)
        if (s.length > 10) return -1
        for ((k, v) in dayMap) {
            if (s == k) return v
        }
        for ((k, v) in dayMap) {
            if (s.contains(k)) return v
        }
        return -1
    }

    private fun toMin(hhmm: String): Int {
        val p = hhmm.split(":")
        if (p.size < 2) return -1
        val h = p[0].toIntOrNull() ?: return -1
        val m = p[1].toIntOrNull() ?: return -1
        return h * 60 + m
    }

    private fun periodRange(text: String): IntArray? {
        val m = Regex("(\\d+)\\s*[-~–—]\\s*(\\d+)\\s*节").find(text)
        if (m != null) {
            val a = m.groupValues[1].toIntOrNull() ?: return null
            val b = m.groupValues[2].toIntOrNull() ?: return null
            return intArrayOf(a, b)
        }
        val one = Regex("第?\\s*(\\d+)\\s*节").find(text)
        if (one != null) {
            val a = one.groupValues[1].toIntOrNull() ?: return null
            return intArrayOf(a, a)
        }
        val bare = Regex("^(\\d+)\\s*[-~–—]\\s*(\\d+)$").find(text.trim())
        if (bare != null) {
            val a = bare.groupValues[1].toIntOrNull() ?: return null
            val b = bare.groupValues[2].toIntOrNull() ?: return null
            return intArrayOf(a, b)
        }
        return null
    }

    private fun looksLikeRoom(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty()) return false
        if (Regex("[A-Za-z]\\s*\\d").containsMatchIn(s)) return true
        val keys = arrayOf("楼", "馆", "场", "机房", "室", "教", "区", "堂", "厅", "中心", "基地")
        for (k in keys) {
            if (s.contains(k) && k != "教") return true
        }
        return Regex("教\\s*[一二三四五六七八九十]").containsMatchIn(s)
    }

    private fun looksLikeWeeks(text: String): Boolean {
        val s = text.trim()
        if (!s.contains("周")) return false
        return Regex("\\d").containsMatchIn(s)
    }

    fun parse(text: String, t: Timetable): List<Course> {
        val up = text.uppercase(Locale.US)
        if (up.contains("BEGIN:VEVENT")) return parseIcs(text, t)
        return parseText(text, t)
    }

    fun parseText(text: String, t: Timetable): List<Course> {
        val out = mutableListOf<Course>()
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.contains("课程名称") || line.contains("课程名")) continue
            var parts = line.split("\t", "|", ";", "；").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size <= 1) {
                parts = line.split(Regex("[\\s,，]+")).map { it.trim() }.filter { it.isNotEmpty() }
            }
            if (parts.size < 2) continue
            var day = -1
            var pStart = -1
            var pEnd = -1
            var weeks = "1-" + t.totalWeeks
            val rest = mutableListOf<String>()
            for (p in parts) {
                if (day < 0) {
                    val d = dayOf(p)
                    if (d >= 0) {
                        day = d
                        continue
                    }
                }
                val pr = periodRange(p)
                if (pr != null && pStart < 0) {
                    pStart = pr[0]
                    pEnd = pr[1]
                    continue
                }
                if (looksLikeWeeks(p)) {
                    weeks = Weeks.normalize(p, t.totalWeeks)
                    continue
                }
                rest.add(p)
            }
            if (day < 0 || pStart < 0 || rest.isEmpty()) continue
            val name = rest[0]
            if (name.isEmpty() || name.length > 30) continue
            var room = ""
            var teacher = ""
            for (i in 1 until rest.size) {
                val s = rest[i]
                if (room.isEmpty() && looksLikeRoom(s)) {
                    room = s
                } else {
                    if (teacher.isEmpty()) teacher = s else teacher = teacher + " " + s
                }
            }
            out.add(Course(name, room, teacher, day, pStart, pEnd, weeks, out.size))
        }
        return out
    }

    fun parseIcs(text: String, t: Timetable): List<Course> {
        val out = mutableListOf<Course>()
        val lines = mutableListOf<String>()
        for (line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.size - 1] = lines[lines.size - 1] + line.substring(1)
            } else {
                lines.add(line)
            }
        }
        var cur: MutableMap<String, String>? = null
        for (line in lines) {
            val up = line.uppercase(Locale.US)
            if (up.startsWith("BEGIN:VEVENT")) {
                cur = mutableMapOf()
                continue
            }
            if (up.startsWith("END:VEVENT")) {
                val m = cur
                if (m != null) {
                    val c = icsEvent(m, t, out.size)
                    if (c != null) out.add(c)
                }
                cur = null
                continue
            }
            val m2 = cur
            if (m2 != null) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).uppercase(Locale.US)
                    val value = line.substring(idx + 1)
                    val base = if (key.contains(";")) key.substringBefore(';') else key
                    if (base == "RRULE" && m2.containsKey(base)) {
                        m2[base] = m2[base] + ";" + value
                    } else if (!m2.containsKey(base)) {
                        m2[base] = value
                    }
                }
            }
        }
        return out
    }

    private fun splitDateTime(raw: String): String {
        val s = raw.trim().substringBefore("Z")
        if (s.contains("T")) return s.substringBefore("T")
        return s
    }

    private fun timeOf(raw: String): String? {
        val s = raw.trim().substringBefore("Z")
        if (!s.contains("T")) return null
        val b = s.substringAfter("T")
        if (b.length < 4) return null
        return b.substring(0, 2) + ":" + b.substring(2, 4)
    }

    private fun periodOfStart(min: Int, t: Timetable): Int {
        var best = 1
        for (i in t.periods.indices) {
            val s = toMin(t.periods[i].start)
            if (s >= 0 && min >= s) best = i + 1
        }
        return best
    }

    private fun periodOfEnd(min: Int, t: Timetable): Int {
        for (i in t.periods.indices) {
            val e = toMin(t.periods[i].end)
            if (e >= 0 && min <= e) return i + 1
        }
        return t.periods.size
    }

    private fun icsEvent(m: Map<String, String>, t: Timetable, color: Int): Course? {
        val summary = m["SUMMARY"]?.trim().orEmpty()
        if (summary.isEmpty()) return null
        val dtStartRaw = m["DTSTART"] ?: return null
        val dtEndRaw = m["DTEND"] ?: dtStartRaw
        val dayCal = Weeks.parseCompact(splitDateTime(dtStartRaw))
        val day = if (dayCal != null) (dayCal.get(Calendar.DAY_OF_WEEK) + 5) % 7 else 0
        val sTime = timeOf(dtStartRaw)
        val eTime = timeOf(dtEndRaw)
        var pStart = if (sTime != null) periodOfStart(toMin(sTime), t) else 1
        var pEnd = if (eTime != null) periodOfEnd(toMin(eTime), t) else pStart
        if (pEnd < pStart) pEnd = pStart
        var weeks = "1-" + t.totalWeeks
        val rrule = m["RRULE"]
        if (rrule != null) {
            val up = rrule.uppercase(Locale.US)
            var count = 0
            var interval = 1
            var until: String? = null
            for (seg in up.split(";")) {
                if (seg.startsWith("COUNT=")) count = seg.substringAfter('=').toIntOrNull() ?: 0
                if (seg.startsWith("INTERVAL=")) interval = seg.substringAfter('=').toIntOrNull() ?: 1
                if (seg.startsWith("UNTIL=")) until = seg.substringAfter('=')
            }
            if (count > 0) {
                if (interval <= 1) {
                    weeks = "1-" + count
                } else {
                    val sb = StringBuilder()
                    for (k in 0 until count) {
                        if (sb.isNotEmpty()) sb.append(",")
                        sb.append(1 + k * interval)
                    }
                    weeks = sb.toString()
                }
            } else if (until != null) {
                val sw = Weeks.weekOfDay(dayCal, t)
                val ew = Weeks.weekOfDay(Weeks.parseCompact(splitDateTime(until)), t)
                if (ew >= sw) weeks = sw.toString() + "-" + ew.toString()
            }
        }
        var room = m["LOCATION"]?.replace("\\n", " ")?.replace("\\,", ",")?.trim().orEmpty()
        val desc = m["DESCRIPTION"]?.replace("\\n", "\n").orEmpty()
        var teacher = ""
        for (d in desc.split("\n")) {
            val s = d.trim()
            if ((s.startsWith("教师") || s.startsWith("老师")) && teacher.isEmpty()) {
                teacher = s.substringAfter(':').substringAfter('：').trim()
            }
            if ((s.startsWith("地点") || s.startsWith("教室")) && room.isEmpty()) {
                room = s.substringAfter(':').substringAfter('：').trim()
            }
        }
        if (pStart > t.periods.size) pStart = t.periods.size
        return Course(summary, room, teacher, day, pStart, pEnd, weeks, color)
    }
}