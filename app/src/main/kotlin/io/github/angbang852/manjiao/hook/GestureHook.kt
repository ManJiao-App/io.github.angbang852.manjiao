package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.ui.MainMenuDialog
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

object GestureHook {

    // 双击窗口 500ms。2026-09-05 教训一：曾砍到 300ms 修"滑动误判"，直接砍掉了
    // 用户偏慢的双击间隔——双击功能整体失效。教训二：双击消费若放在 UP，
    // 快手在第二击 DOWN 即触发点赞(拦 UP 太晚)，且"有 DOWN 无 UP"会被快手
    // 判成长按弹出分享菜单。故双击必须在 DOWN 时消费(快手收不到第二击任何事件)。
    // 滑动误判由"干净 tap 才计数"治本(见 ACTION_UP)：滑动 UP 位移大不入计数。
    private const val TAP_TIMEOUT = 500L
    private const val TAP_SLOP = 200f
    // 0=IDLE, 1=已干净单击, 2=已双击(未拦截,等待三击)
    @Volatile private var tapState = 0
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    // 本次手势起点（DOWN 记录，UP 校验位移：干净 tap 才入双击计数）
    private var downX = 0f
    private var downY = 0f
    // 双击命中后被消费手势的后续事件连带消费（快手绝不能收到 UP，否则长按误触）
    @Volatile private var consumeUp = false

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        Logger.d("GestureHook: hook() called")
        val actCls = Reflect.findClass("android.app.Activity", cl) ?: run {
            Logger.d("GestureHook: Activity class not found")
            return
        }
        val m = Reflect.findMethod(actCls, "dispatchTouchEvent", 1) ?: run {
            Logger.d("GestureHook: dispatchTouchEvent not found")
            return
        }
        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("gs.dispatch").intercept { chain ->
                val ev = chain.args[0] as? MotionEvent
                val act = chain.thisObject as? Activity
                if (ev != null && act != null && isKsActivity(act)) {
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = ev.x; downY = ev.y
                            if (handleDown(act, ev)) { consumeUp = true; return@intercept true }
                        }
                        MotionEvent.ACTION_MOVE -> {
                            // 位移中途超 slop 即判滑动，提前失效（双击计数作废）
                            if (Math.abs(ev.x - downX) >= TAP_SLOP || Math.abs(ev.y - downY) >= TAP_SLOP) tapState = 0
                            if (consumeUp) return@intercept true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (consumeUp) { consumeUp = false; return@intercept true }
                            // ★ 干净 tap 才入计数：DOWN→UP 位移超 slop = 滑动手势，
                            // 重置计数（快速连滑无论多密都不会误判双击）
                            val moved = Math.abs(ev.x - downX) >= TAP_SLOP || Math.abs(ev.y - downY) >= TAP_SLOP
                            if (moved) tapState = 0
                            else {
                                val gap = SystemClock.uptimeMillis() - lastTapTime
                                lastTapTime = SystemClock.uptimeMillis(); lastTapX = ev.x; lastTapY = ev.y
                                // 新 tap 链起点（超窗=旧链已断，重置为单击态）
                                if (tapState == 0 || gap >= TAP_TIMEOUT) tapState = 1
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (consumeUp) { consumeUp = false; return@intercept true }
                            tapState = 0
                        }
                    }
                }
                chain.proceed()
            }

        Logger.d("GestureHook: hooked Activity.dispatchTouchEvent")
    }

    // 快手全系 Activity 均响应双击（用户决定：别的地方能弹就弹）
    private fun isKsActivity(act: Activity): Boolean {
        val cn = act.javaClass.name
        return cn.contains("gifshow") || cn.contains("kuaishou") || cn.contains("yxcorp")
    }

    /** 在 DOWN 时做双击/三击检测。@return true=消费该 DOWN 及后续事件(快手收不到), false=放行 */
    private fun handleDown(act: Activity, ev: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        val dx = Math.abs(ev.x - lastTapX)
        val dy = Math.abs(ev.y - lastTapY)
        val inWindow = now - lastTapTime < TAP_TIMEOUT && dx < TAP_SLOP && dy < TAP_SLOP

        if (!inWindow) return false

        when (tapState) {
            1 -> {
                tapState = 2
                val noDblLike = Prefs.bool(Prefs.K_GS_NO_DBL_LIKE, false)
                val openComment2 = Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false) &&
                    Prefs.int(Prefs.K_GS_OPEN_COMMENT_TAPS, 2) == 2
                val openMenu2 = Prefs.bool(Prefs.K_GS_OPEN_MENU, false) &&
                    Prefs.int(Prefs.K_GS_OPEN_MENU_TAPS, 2) == 2
                Logger.d("gs: double-tap detected noDblLike=$noDblLike oc=$openComment2 om=$openMenu2")
                if (noDblLike || openComment2 || openMenu2) {
                    // 消费第二击 DOWN（含后续 MOVE/UP）：快手双击点赞在 DOWN 触发，
                    // 且收不到 UP 会被判长按——必须整段吞掉
                    if (openComment2) openCommentPanel(act)
                    else if (openMenu2) MainMenuDialog.show(act)
                    tapState = 0
                    return true
                }
                return false
            }
            2 -> {
                tapState = 0
                val openComment3 = Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false) &&
                    Prefs.int(Prefs.K_GS_OPEN_COMMENT_TAPS, 2) == 3
                val openMenu3 = Prefs.bool(Prefs.K_GS_OPEN_MENU, false) &&
                    Prefs.int(Prefs.K_GS_OPEN_MENU_TAPS, 2) == 3
                if (openComment3 || openMenu3) {
                    Logger.d("gs: triple-tap oc=$openComment3 om=$openMenu3")
                    if (openComment3) openCommentPanel(act)
                    else if (openMenu3) MainMenuDialog.show(act)
                    return true
                }
                return false
            }
            else -> return false
        }
    }

    private fun openCommentPanel(act: Activity) {
        try {
            val decor = act.window.decorView
            findAndClickComment(decor)
        } catch (t: Throwable) { Logger.d("gs openComment fail: ${t.message}") }
    }

    // 找评论按钮并模拟真实点击。2026-09-05 实证：performClick 被调但面板不开——
    // contentDescription 含"评论"的第一个 View 常是评论数文本而非按钮，且快手
    // 自定义按钮吃 touch 流不吃 performClick。策略：①优先 id 名含 comment 且
    // 可点击的控件 ②contentDescription 兜底 ③对命中 View dispatchTouchEvent
    // 完整 DOWN→UP（走真实 touch 管线，等效手指点击）
    private fun findAndClickComment(root: View): Boolean {
        var byId: View? = null
        var byCd: View? = null
        walkViews(root) { v ->
            if (byId != null && byCd != null) return@walkViews
            if (v.visibility != View.VISIBLE) return@walkViews
            val idName = try { val rid = v.id; if (rid != View.NO_ID) v.resources.getResourceEntryName(rid) else "" } catch (_: Throwable) { "" }
            if (byId == null && idName.isNotEmpty() && idName.contains("comment", true) && v.isClickable && v.width > 0) byId = v
            val cd = v.contentDescription?.toString() ?: ""
            if (byCd == null && cd.contains("评论") && v.isClickable && v.width > 0) byCd = v
        }
        val target = byId ?: byCd
        if (target == null) { Logger.d("gs: comment view not found"); return false }
        Logger.d("gs: comment target cls=${target.javaClass.simpleName}")
        val t0 = SystemClock.uptimeMillis()
        val x = (target.left + target.right) / 2f
        val y = (target.top + target.bottom) / 2f
        try {
            var ev = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0)
            target.dispatchTouchEvent(ev); ev.recycle()
            ev = MotionEvent.obtain(t0, t0 + 60L, MotionEvent.ACTION_UP, x, y, 0)
            target.dispatchTouchEvent(ev); ev.recycle()
            Logger.d("gs: comment touched")
        } catch (t: Throwable) { Logger.d("gs: comment touch fail: ${t.message}") }
        return true
    }

    private fun walkViews(v: View, cb: (View) -> Unit) {
        cb(v)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { walkViews(it, cb) }
    }
}
