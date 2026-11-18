package com.dsh.timetable

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 「登录失效」通知：点开直接跳到内置浏览器的那条官网地址，用户重新登录后就能再识别一次。
 */
object Notifier {
    private const val CHANNEL = "timetable_update"
    private const val ID_EXPIRED = 1001
    private const val CHANNEL_SILENT = "auto_update"
    private const val ID_UPDATING = 1002

    private fun ensureChannel(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "课表更新", NotificationManager.IMPORTANCE_DEFAULT)
                ch.description = "自动更新课表失败或登录失效时提醒"
                nm.createNotificationChannel(ch)
            }
        }
        return nm
    }

    /** 登录失效：点通知 → 打开官网登录页 */
    fun notifyLoginExpired(ctx: Context, url: String, site: String) {
        val nm = ensureChannel(ctx) ?: return
        val where = if (site.isNotEmpty()) site else "教务系统"
        val target = if (url.isNotEmpty()) url else "https://ngjw.nyist.edu.cn/admin/caslogin"

        val i = Intent(ctx, WebImportActivity::class.java)
        i.putExtra("url", target)
        i.putExtra("auto", true)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val pi = PendingIntent.getActivity(
            ctx, ID_EXPIRED, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            android.app.Notification.Builder(ctx, CHANNEL)
        else
            @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)

        val n = builder
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("课表没更新成功：" + where + " 登录失效了")
            .setContentText("点这里重新登录，登录完点「识别本页课表」就能同步")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setStyle(android.app.Notification.BigTextStyle()
                .bigText("课表没更新成功：" + where + " 的登录状态已失效。\n点这里打开官网登录页，重新登录后回到课表页点「识别本页课表」即可同步。"))
            .build()
        try {
            nm.notify(ID_EXPIRED, n)
        } catch (e: Throwable) {
        }
    }

    private fun ensureSilentChannel(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_SILENT) == null) {
                val ch = NotificationChannel(CHANNEL_SILENT, "自动更新", NotificationManager.IMPORTANCE_LOW)
                ch.description = "后台自动更新课表时的静默进度"
                ch.setSound(null, null)
                ch.enableVibration(false)
                nm.createNotificationChannel(ch)
            }
        }
        return nm
    }

    /** 静默的「正在自动更新」进度通知：不出声、不弹窗，只在下拉里 */
    fun notifyUpdating(ctx: Context, text: String, progress: Int, max: Int, done: Boolean) {
        val nm = ensureSilentChannel(ctx) ?: return
        val i = Intent(ctx, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pi = PendingIntent.getActivity(ctx, ID_UPDATING, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            android.app.Notification.Builder(ctx, CHANNEL_SILENT)
        else
            @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (done) "课表已自动更新" else "正在自动更新课表")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(!done)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (max > 0 && !done) builder.setProgress(max, progress, false) else builder.setProgress(0, 0, false)
        try { nm.notify(ID_UPDATING, builder.build()) } catch (e: Throwable) { }
    }

    fun clearUpdating(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(ID_UPDATING)
        } catch (e: Throwable) { }
    }
}
