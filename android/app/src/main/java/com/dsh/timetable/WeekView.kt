package com.dsh.timetable

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Calendar

class WeekView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var timetable: Timetable? = null
    private var week = 1
    private var todayIndex = -1
    private var nowRatio = -1f
    private val painter = GridPainter(resources.displayMetrics.density)

    /** 点中某张课程卡片时回调 */
    var onCourseClick: ((Course) -> Unit)? = null

    /** 左右滑动切换周：-1 上一周，+1 下一周 */
    var onWeekSwipe: ((Int) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    fun setData(t: Timetable?, weekNo: Int) {
        timetable = t
        week = weekNo
        val now = Calendar.getInstance()
        todayIndex = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        nowRatio = if (t == null) -1f else painter.offsetFor(t, mins)
        invalidate()
    }

    /** 切周时做一个很短的淡入淡出，别让整屏内容硬跳 */
    fun fadeIn(duration: Long) {
        alpha = 0.35f
        animate().alpha(1f).setDuration(duration).start()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                swiping = true
                painter.pressedCourse = courseAt(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(e.x - downX)
                val dy = Math.abs(e.y - downY)
                if ((dx > 12f || dy > 12f) && painter.pressedCourse != null) {
                    painter.pressedCourse = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                swiping = false
                painter.pressedCourse = null
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                val limit = 56f * resources.displayMetrics.density
                val isSwipe = swiping && Math.abs(dx) > limit && Math.abs(dx) > Math.abs(dy) * 1.6f
                swiping = false
                painter.pressedCourse = null
                invalidate()
                if (isSwipe) {
                    // 手指往左滑 → 看下一周
                    onWeekSwipe?.invoke(if (dx < 0) 1 else -1)
                    return true
                }
                val c = courseAt(e.x, e.y)
                if (c != null) {
                    onCourseClick?.invoke(c)
                    return true
                }
            }
        }
        return true
    }

    private fun courseAt(x: Float, y: Float): Course? {
        for (i in painter.hits.indices) {
            if (painter.hits[i].contains(x, y)) return painter.hitCourses[i]
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = timetable ?: return
        painter.draw(
            canvas,
            width,
            height,
            t,
            week,
            todayIndex,
            nowRatio,
            true
        )
    }
}
