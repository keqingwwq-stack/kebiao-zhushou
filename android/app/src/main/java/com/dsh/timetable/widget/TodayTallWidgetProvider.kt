package com.dsh.timetable.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** 课表 今日 2x3：列出今天的课，底部灰字提醒下一节 */
class TodayTallWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) TodayWidgetProvider.updateTall(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: android.os.Bundle
    ) {
        TodayWidgetProvider.updateTall(context, manager, id)
    }
}
