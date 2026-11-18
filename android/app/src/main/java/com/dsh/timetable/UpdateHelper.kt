package com.dsh.timetable

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * 应用内更新：把新 APK 交给系统安装器，系统会「替换旧版本」而不是并存两份。
 * 数据（课表、书架、设置）都存在应用私有目录里，覆盖安装不会丢。
 */
object UpdateHelper {

    /** 当前版本，例如 "1.1 (2)" */
    fun versionText(act: Activity): String {
        return try {
            val pi = act.packageManager.getPackageInfo(act.packageName, 0)
            val name = pi.versionName ?: "1.0"
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "$name ($code)"
        } catch (e: Throwable) {
            "未知"
        }
    }

    /** 把用户选的 APK 复制到 cache 再装（外部 Uri 需要读权限，交给安装器更容易失败） */
    fun installFromUri(act: Activity, uri: Uri) {
        try {
            val name = "update-${System.currentTimeMillis()}.apk"
            val dst = File(act.cacheDir, name)
            act.contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    toast(act, "读不到这个文件")
                    return
                }
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
            installFile(act, dst)
        } catch (e: Throwable) {
            toast(act, "更新失败：${e.message}")
        }
    }

    /** 装一个本地 APK 文件 */
    fun installFile(act: Activity, file: File) {
        if (!file.exists() || file.length() < 1024L) {
            toast(act, "文件不完整")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            toast(act, "请允许「安装未知应用」，再点一次更新")
            try {
                val it = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${act.packageName}"))
                act.startActivity(it)
            } catch (e: Throwable) {
                // 某些系统没有这个页面，忽略
            }
            return
        }
        // 用自带的 ApkProvider 暴露 cache 里的文件（不依赖 androidx）
        val apkUri: Uri = Uri.parse("content://${act.packageName}.apk/${file.name}")
        val it = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            act.startActivity(it)
            toast(act, "正在安装新版本，会替换掉旧版")
        } catch (e: Throwable) {
            toast(act, "打不开安装器：${e.message}")
        }
    }

    private fun toast(act: Activity, msg: String) {
        act.runOnUiThread { Toast.makeText(act, msg, Toast.LENGTH_LONG).show() }
    }
}
