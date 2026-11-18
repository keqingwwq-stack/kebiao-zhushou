package com.dsh.timetable.widget

import com.dsh.timetable.WidgetScheduler
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dsh.timetable.MainActivity
import com.dsh.timetable.R
import com.dsh.timetable.WidgetPainter

class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, manager, id, false)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: android.os.Bundle
    ) {
        update(context, manager, id, false)
    }

    companion object {
        /** 课程数据变化后，刷新桌面上所有已添加的小组件 */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val todayIds = manager.getAppWidgetIds(
                android.content.ComponentName(context, TodayWidgetProvider::class.java)
            )
            for (id in todayIds) update(context, manager, id, false)
            val tallIds = manager.getAppWidgetIds(
                android.content.ComponentName(context, TodayTallWidgetProvider::class.java)
            )
            for (id in tallIds) updateTall(context, manager, id)
            val weekIds = manager.getAppWidgetIds(
                android.content.ComponentName(context, WeekWidgetProvider::class.java)
            )
            for (id in weekIds) update(context, manager, id, true)
        }

        /** 2x3 竖版今日卡片 */
        fun updateTall(context: Context, manager: AppWidgetManager, id: Int) {
            val opts = manager.getAppWidgetOptions(id)
            val (w, h) = sizeOf(opts, 110, 180)
            val bmp = WidgetPainter.todayTall(context, w, h)
            val views = RemoteViews(context.packageName, R.layout.widget_root)
            views.setImageViewBitmap(R.id.widgetImage, bmp)
            val intent = Intent(context, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(context, 0, intent, flags)
            views.setOnClickPendingIntent(R.id.widgetImage, pi)
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)
            manager.updateAppWidget(id, views)
            try { WidgetScheduler.scheduleNext(context) } catch (e: Throwable) { }
        }


        /**
         * 取小组件当前的尺寸（dp）。
         * 只用 OPTION_APPWIDGET_MIN_WIDTH 会拿到「最小可容纳尺寸」，
         * 比桌面实际占位小很多，位图被拉伸后字会变形、还会和「第 N 周」叠在一起；
         * 所以优先用 MAX，其次用 Android 12+ 的 SIZES 列表。
         */
        private fun sizeOf(opts: android.os.Bundle, defW: Int, defH: Int): Pair<Int, Int> {
            var w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defW)
            var h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defH)
            val maxW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val maxH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            if (maxW > w) w = maxW
            if (maxH > h) h = maxH
            try {
                val sizes = opts.getParcelableArray("appWidgetSizes")
                if (sizes != null && sizes.isNotEmpty()) {
                    val first = sizes[0] as? android.util.SizeF
                    if (first != null && first.width > 0f && first.height > 0f) {
                        w = Math.round(first.width)
                        h = Math.round(first.height)
                    }
                }
            } catch (e: Throwable) { }
            return Pair(w.coerceIn(60, 600), h.coerceIn(40, 600))
        }

        fun update(context: Context, manager: AppWidgetManager, id: Int, weekStyle: Boolean) {
            val opts = manager.getAppWidgetOptions(id)
            val (w, h) = sizeOf(opts, 180, 70)
            val bmp = if (weekStyle) WidgetPainter.week(context, w, h) else WidgetPainter.today(context, w, h)
            val views = RemoteViews(context.packageName, R.layout.widget_root)
            views.setImageViewBitmap(R.id.widgetImage, bmp)
            // 点一下打开 App（圆角与白底都画在位图里，图片铺满整个小组件）
            val intent = Intent(context, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(context, 0, intent, flags)
            views.setOnClickPendingIntent(R.id.widgetImage, pi)
            views.setOnClickPendingIntent(R.id.widgetRoot, pi)
            manager.updateAppWidget(id, views)
            try { WidgetScheduler.scheduleNext(context) } catch (e: Throwable) { }
        }
    }
}