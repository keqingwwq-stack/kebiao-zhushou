package com.dsh.timetable

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.dsh.timetable.widget.WidgetTickReceiver
import java.util.Calendar

/**
 * 小组件「到点自己动」的调度器。
 *
 * Android 规定 AppWidgetProvider 的 updatePeriodMillis 最短 30 分钟，光靠它刷新，
 * 「上课前 30 分钟切到下一节」「下课那一刻变文案」都会晚最多半小时。
 * 所以每次重画完，都算出下一个「状态会变的时刻」，用 AlarmManager 精确叫醒自己：
 *   · 每节课的 (开始 - 30 分钟)  → 卡片要提前切到这门课
 *   · 每节课的开始时刻            → 文案从「还有 N 分钟」变成「正在上」
 *   · 每节课的结束时刻            → 该提醒下一节了
 *   · 次日 00:05                  → 跨天，「今天」的课表要换
 */
object WidgetScheduler {

    private const val REQ = 20261118

    fun scheduleNext(ctx: Context) {
        val t = Store.load(ctx)
        val now = System.currentTimeMillis()
        var next = nextMidnight(now)

        for (addDay in 0..7) {
            val dayCal = Calendar.getInstance()
            dayCal.timeInMillis = now
            dayCal.add(Calendar.DAY_OF_YEAR, addDay)
            val dayIdx = (dayCal.get(Calendar.DAY_OF_WEEK) + 5) % 7   // 0 = 周一
            val week = Weeks.weekOfDay(dayCal, t)

            for (c in t.courses) {
                if (c.day != dayIdx) continue
                if (!Weeks.inWeek(c.weeks, week)) continue
                val sMin = minuteOf(t, c.start, false)
                val eMin = minuteOf(t, c.end, true)
                for (m in intArrayOf(sMin - 30, sMin, eMin)) {
                    val at = atMinute(now, addDay, m)
                    if (at > now + 60_000L && at < next) next = at
                }
            }
        }

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, REQ,
            Intent(ctx, WidgetTickReceiver::class.java).setAction(WidgetTickReceiver.ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 不用精确闹钟权限：setAndAllowWhileIdle 在 Doze 下也能跑，误差通常 1 分钟内
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    private fun minuteOf(t: Timetable, period: Int, isEnd: Boolean): Int {
        val p = t.periods.getOrNull(period - 1) ?: return if (isEnd) 0 else 8 * 60
        val s = if (isEnd) p.end else p.start
        val hm = s.split(":")
        return if (hm.size == 2) hm[0].toInt() * 60 + hm[1].toInt() else 8 * 60
    }

    private fun atMinute(now: Long, addDay: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, addDay)
        cal.set(Calendar.HOUR_OF_DAY, minute / 60)
        cal.set(Calendar.MINUTE, minute % 60)
        cal.set(Calendar.SECOND, 5)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun nextMidnight(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 5)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
