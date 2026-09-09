package io.github.angbang852.manjiao.hook

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.angbang852.manjiao.ui.MainMenuDialog
import io.github.angbang852.manjiao.util.IconData
import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.api.XposedInterface

object SharePanelHook {

    private const val TAG_OVERLAY = "slowkick_overlay"
    private const val TAG_ENTRY = "slowkick_entry"
    private const val MAX_RETRY = 50
    private const val RETRY_MS = 100L
    private val handler = Handler(Looper.getMainLooper())

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        hookDialogShow(xp, cl)
        hookDialogDismiss(xp, cl)
        Logger.d("SharePanelHook installed")
    }

    private fun hookDialogShow(xp: XposedInterface, cl: ClassLoader) {
        val m = Dialog::class.java.getDeclaredMethod("show")
        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("dialog.show").intercept { chain ->
            chain.proceed()
            try {
                val dlg = chain.thisObject as Dialog
                Logger.d("dialog show: ${dlg.javaClass.name}")
                val decor = dlg.window?.decorView as? ViewGroup
                if (decor != null) {
                    decor.post { injectEntry(dlg.context, decor, dlg, 0) }
                }
            } catch (_: Throwable) {}
            null
        }
    }

    private fun hookDialogDismiss(xp: XposedInterface, cl: ClassLoader) {
        for (name in arrayOf("dismiss", "hide")) {
            val m = try { Dialog::class.java.getDeclaredMethod(name) } catch (_: Throwable) { null } ?: continue
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("dialog.$name").intercept { chain ->
                try {
                    val dlg = chain.thisObject as Dialog
                    val decor = dlg.window?.decorView as? ViewGroup
                    if (decor != null) removeOverlay(decor)
                } catch (_: Throwable) {}
                chain.proceed()
                null
            }
        }
    }

    private fun injectEntry(ctx: Context, root: ViewGroup, hostDialog: Dialog, retry: Int) {
        if (retry >= MAX_RETRY) return
        val target = findReplaceTarget(root) ?: run {
            root.postDelayed({ injectEntry(ctx, root, hostDialog, retry + 1) }, RETRY_MS)
            return
        }
        val tv = target.first
        val anchor = target.second
        val ok = insertIntoRow(ctx, root, tv, anchor, hostDialog)
        if (!ok) {
            root.postDelayed({ injectEntry(ctx, root, hostDialog, retry + 1) }, RETRY_MS)
            return
        }
        Logger.d("entry transformed in place")
        watchEntry(ctx, root, hostDialog)
    }

    private fun watchEntry(ctx: Context, root: ViewGroup, hostDialog: Dialog) {
        root.postDelayed({
            try {
                if (!hostDialog.isShowing) return@postDelayed
                // 数据绑定可能把图标/文字重置回"更多"，每 400ms 幂等重刷
                val tagged = root.findViewWithTag<View>(TAG_OVERLAY)
                if (tagged == null || tagged.parent == null) {
                    injectEntry(ctx, root, hostDialog, 0)
                    return@postDelayed
                }
                val target = findReplaceTarget(root)
                if (target != null) transformTarget(ctx, target.first, target.second, hostDialog)
                watchEntry(ctx, root, hostDialog)
            } catch (_: Throwable) {}
        }, 400)
    }

    private fun removeOverlay(root: ViewGroup) {
        val v = root.findViewWithTag<View>(TAG_OVERLAY) ?: return
        try { v.tag = null } catch (_: Throwable) {}
        Logger.d("overlay removed")
    }

    private fun findReplaceTarget(root: ViewGroup): Pair<TextView, View>? {
        // ManJiao 入口 = 覆盖分享面板的"保存到相册"行（用户指定位置）。
        // 严禁碰"下载"（长按面板的原生下载，之前候选词含它导致下载被劫持）。
        val candidates = arrayOf("保存到相册")
        for (text in candidates) {
            val tv = findTextExact(root, text)
            if (tv != null) {
                val anchor = findActionAnchor(root, tv)
                if (anchor != null) return Pair(tv, anchor)
            }
        }
        return null
    }

    // 原地改造"更多/稍后再看"项为 ManJiao：换图标+文字+劫持点击。
    // 不插入/删除任何 view（分享行是 RecyclerView，改结构滑动时会崩溃回收 NPE）
    private fun insertIntoRow(ctx: Context, root: ViewGroup, tv: TextView, anchor: View, hostDialog: Dialog): Boolean {
        return try {
            transformTarget(ctx, tv, anchor, hostDialog)
            true
        } catch (t: Throwable) {
            Logger.d("transformTarget fail: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun transformTarget(ctx: Context, tv: TextView, anchor: View, hostDialog: Dialog) {
        anchor.tag = TAG_OVERLAY
        val iv = findLargestImageView(anchor, null)
        if (iv != null) {
            val forceIcon = Runnable {
                try {
                    val d = IconData.get(ctx)
                    // 幂等短路：同一 drawable 实例时跳过，避免 400ms 重刷引起
                    // setImageDrawable 全流程（requestLayout+invalidate）→ 图标闪烁
                    if (iv.drawable !== d) {
                        iv.setImageDrawable(d)
                        iv.colorFilter = null
                        iv.imageTintList = null
                    }
                } catch (_: Throwable) {}
            }
            forceIcon.run()
            iv.postDelayed(forceIcon, 100)
            iv.postDelayed(forceIcon, 300)
        }
        try {
            if (tv.text?.toString() != "ManJiao") tv.text = "ManJiao"
        } catch (_: Throwable) {}
        val open = Runnable {
            try { hostDialog.dismiss() } catch (_: Throwable) {}
            handler.postDelayed({ MainMenuDialog.show(ctx) }, 120)
        }
        anchor.setOnClickListener { open.run() }
        iv?.setOnClickListener { open.run() }
        tv.setOnClickListener { open.run() }
    }

    private fun findActionAnchor(root: ViewGroup, tv: TextView): View? {
        var view: View = tv
        var depth = 0
        while (view.parent is ViewGroup && view.parent != root && depth < 8) {
            val parent = view.parent as ViewGroup
            if (hasImageView(parent) && parent.childCount <= 5) return parent
            view = parent
            depth++
        }
        return tv
    }

    private fun hasImageView(v: View): Boolean {
        if (v is ImageView) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (hasImageView(v.getChildAt(i) ?: continue)) return true
            }
        }
        return false
    }

    private fun findTextExact(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val ch = root.getChildAt(i) ?: continue
                findTextExact(ch, text)?.let { return it }
            }
        }
        return null
    }

    private fun findLargestImageView(v: View, best: ImageView?): ImageView? {
        if (v.visibility == View.GONE) return best
        var result = best
        if (v is ImageView) {
            if (result == null || viewArea(v) > viewArea(result)) result = v
        }
        if (v !is ViewGroup) return result
        for (i in 0 until v.childCount) {
            result = findLargestImageView(v.getChildAt(i) ?: continue, result)
        }
        return result
    }

    private fun viewArea(v: View): Int {
        val w = Math.max(0, v.width)
        val h = Math.max(0, v.height)
        val lp = v.layoutParams
        var fw = w
        var fh = h
        if (lp != null) {
            if (fw == 0 && lp.width > 0) fw = lp.width
            if (fh == 0 && lp.height > 0) fh = lp.height
        }
        return fw * fh
    }
}