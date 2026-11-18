package com.dsh.timetable.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dsh.timetable.WidgetScheduler

/** AlarmManager 叫醒时重画所有小组件，然后排下一个时刻。 */
class WidgetTickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        TodayWidgetProvider.refreshAll(ctx)
        WidgetScheduler.scheduleNext(ctx)
    }

    companion object {
        const val ACTION_TICK = "com.dsh.timetable.WIDGET_TICK"
    }
}
