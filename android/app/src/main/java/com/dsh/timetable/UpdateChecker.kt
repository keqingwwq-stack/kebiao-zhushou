package com.dsh.timetable

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在线更新：从「更新地址」拉一个 JSON（或直接给 .apk 链接），下载后交给系统安装器覆盖旧版。
 *
 * JSON 格式（放服务器或网盘直链都行）：
 *   { "versionCode": 3, "versionName": "1.2", "url": "https://.../app.apk", "notes": "改了什么" }
 *
 * 如果地址直接指向 .apk，也支持：版本号未知，UI 会先问一句再下载。
 */
object UpdateChecker {

    private const val PREFS = "update"
    private const val KEY_URL = "url"

    /**
     * 默认更新地址：放在 GitHub 仓库的 updates/update.json，用 jsDelivr 的 CDN 取（国内可直连）。
     * 用户可以在设置里覆盖，覆盖后以用户填的为准。
     */
    const val DEFAULT_URL = "https://cdn.jsdelivr.net/gh/keqingwwq-stack/kebiao-zhushou@main/update.json"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    /** 真正生效的地址：用户填过就用用户的，否则用默认的 */
    fun updateUrl(ctx: Context): String {
        val custom = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "") ?: ""
        return if (custom.trim().isEmpty()) DEFAULT_URL else custom.trim()
    }

    fun isDefaultUrl(ctx: Context): Boolean =
        (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, "") ?: "").trim().isEmpty()

    fun resetUrl(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_URL).apply()
    }

    fun setUpdateUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, url.trim()).apply()
    }

    fun currentVersionCode(ctx: Context): Int {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
        } catch (e: Throwable) {
            PackageManager.GET_META_DATA // 忽略，返回 0
            0
        }
    }

    /** 阻塞调用，请在子线程里跑 */
    fun fetch(srcUrl: String): Info {
        val conn = (URL(srcUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TimetableWidget/Android")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val text = conn.inputStream.bufferedReader().use { r -> r.readText() }
            val trimmed = text.trim()
            if (trimmed.startsWith("{")) {
                val o = JSONObject(trimmed)
                val vc = o.optInt("versionCode", 0)
                val vn = o.optString("versionName", "")
                val apk = o.optString("url", "")
                if (apk.isEmpty()) throw IllegalStateException("JSON 里没有 url 字段")
                return Info(vc, vn, apk, o.optString("notes", ""))
            }
            // 不是 JSON：当成直接给 APK 的链接
            return Info(-1, "", srcUrl, "")
        } finally {
            conn.disconnect()
        }
    }

    /** 阻塞下载，返回本地 APK 文件；onStep(done, total) 在下载线程里回调 */
    fun download(apkUrl: String, dest: File, onStep: (Long, Long) -> Unit): File {
        var conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TimetableWidget/Android")
        }
        var code = conn.responseCode
        if (code in 300..399) {
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            if (loc.isNullOrEmpty()) throw IllegalStateException("跳转地址为空")
            conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "TimetableWidget/Android")
            }
            code = conn.responseCode
        }
        if (code != 200) throw IllegalStateException("下载失败 HTTP $code")
        val total = conn.contentLengthLong
        if (dest.exists()) dest.delete()
        var done = 0L
        var last = 0L
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 256 * 1024) {
                        last = done
                        onStep(done, total)
                    }
                }
                out.flush()
            }
        }
        conn.disconnect()
        onStep(done, total)
        return dest
    }
}
