package io.github.angbang852.manjiao.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

object GoldFloatHook {
    private val handler = Handler(Looper.getMainLooper())

    // ★ 金币红包浮窗真实类名（手机 dumpsys 实测抓到）：
    // com.kuaishou.growth.pendant.coin.core.kds.unionwidget.UnionView            —— 299x299 可拖动浮球主体
    // com.kuaishou.growth.pendant.coin.core.kds.unionwidget.absorb.UnionAbsorbView —— 140x175 吸附侧边收纳条
    // com.kuaishou.growth.pendant.ui.widget.PendantDrawerView                    —— 抽屉挂件
    // 之前只 hook WindowManager.addView 拦不到：它们是 addView 到 Activity DecorView 的，
    // 不经过 WindowManagerImpl —— 所以"隐藏不了"。改成直接 hook 这三个类的 onAttachedToWindow
    private val FLOAT_CLASSES = arrayOf(
        "com.kuaishou.growth.pendant.coin.core.kds.unionwidget.UnionView",
        "com.kuaishou.growth.pendant.coin.core.kds.unionwidget.absorb.UnionAbsorbView",
        "com.kuaishou.growth.pendant.ui.widget.PendantDrawerView"
    )

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        hookAddView(xp, cl)
        hookFloatClasses(xp, cl)
        Logger.d("GoldFloatHook installed")
    }

    // 挂点一：浮窗类自身的 onAttachedToWindow —— 每次浮窗出现（含拖动后重新 attach）必经
    private fun hookFloatClasses(xp: XposedInterface, cl: ClassLoader) {
        for (cn in FLOAT_CLASSES) {
            Logger.safe("gold.cls.$cn") {
                val c = Reflect.findClass(cn, cl) ?: run { Logger.d("gold cls not found: $cn"); return@safe }
                val m = try { c.getDeclaredMethod("onAttachedToWindow") } catch (_: Throwable) { null }
                if (m == null) {
                    // 没有覆写就用 onWindowVisibilityChanged 兜底
                    Logger.d("gold cls no onAttachedToWindow: $cn")
                    return@safe
                }
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("gold.attach.$cn")
                    .intercept { chain ->
                        chain.proceed()
                        try {
                            val v = chain.thisObject as? View ?: return@intercept null
                            val p = v.parent
                            Logger.always("gold float attach: $cn parent=${p?.javaClass?.name}")
                            scheduleHide(v)
                        } catch (_: Throwable) {}
                        null
                    }
                // ★ 防御二：挂件类若覆写了 setVisibility，快手拖动/重显时会调
                // setVisibility(VISIBLE) 把它 show 回来——hook 住立即再隐（仅三个类，零性能开销）
                Logger.safe("gold.vis.$cn") {
                    val vm = try { c.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
                    if (vm != null) {
                        xp.hook(vm).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("gold.vis.$cn")
                            .intercept { chain ->
                                chain.proceed()
                                try {
                                    val v = chain.thisObject as? View ?: return@intercept null
                                    if ((chain.args[0] as Int) == View.VISIBLE) {
                                        Logger.always("gold float re-show: ${v.javaClass.simpleName}")
                                        scheduleHide(v)
                                    }
                                } catch (_: Throwable) {}
                                null
                            }
                    }
                }
            }
        }
    }

    private fun scheduleHide(v: View) {
        // attach 时尺寸可能还是 0，分多个时机隐藏 + 兜底重试（长重试防快手延迟恢复显示）
        hideIfOn(v)
        v.post { hideIfOn(v) }
        handler.postDelayed({ hideIfOn(v) }, 500)
        handler.postDelayed({ hideIfOn(v) }, 2000)
        handler.postDelayed({ hideIfOn(v) }, 5000)
        handler.postDelayed({ hideIfOn(v) }, 10000)
    }

    private fun hideIfOn(v: View) {
        try {
            if (v.parent == null) return
            if (!Prefs.bool(Prefs.K_IMM_GOLD, false)) return
            if (v.visibility == View.GONE) return
            v.visibility = View.GONE
            Logger.always("gold float HIDDEN: ${v.javaClass.simpleName}")
        } catch (_: Throwable) {}
    }

    private fun hookAddView(xp: XposedInterface, cl: ClassLoader) {
        for (cn in arrayOf("android.view.WindowManagerImpl", "android.view.WindowManagerGlobal")) {
            val c = Reflect.findClass(cn, cl) ?: continue
            for (m in c.declaredMethods) {
                if (m.name != "addView") continue
                if (m.parameterTypes.size < 2) continue
                if (m.parameterTypes[0] != View::class.java) continue
                Logger.safe("gold.hook.$cn") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("gold.$cn.addView").intercept { chain ->
                        chain.proceed()
                        try { onViewAdded(chain.args[0] as? View) } catch (_: Throwable) {}
                        null
                    }
                }
            }
        }
    }

    private fun onViewAdded(v: View?) {
        if (v == null) return
        handler.postDelayed({
            Logger.safe("gold.check") { checkFloat(v) }
        }, 800)
    }

    private fun checkFloat(v: View) {
        if (v.width == 0 || v.height == 0) return
        val w = v.width; val h = v.height
        if (w > 480 || h > 480) return
        val txt = collectText(v)
        val cd = v.contentDescription?.toString() ?: ""
        val idName = try { val rid = v.id; if (rid != View.NO_ID) v.resources.getResourceEntryName(rid) else "" } catch (_: Throwable) { "" }
        Logger.d("gold win cls=${v.javaClass.simpleName} id=$idName w=$w h=$h t=${txt.take(15)} cd=${cd.take(15)} kids=${if (v is ViewGroup) v.childCount else 0}")
        if (!Prefs.bool(Prefs.K_IMM_GOLD, false)) return
        // 浮窗类名直接命中（UnionView 等）也走 WindowManager 路径时兜底
        val clsHit = FLOAT_CLASSES.any { v.javaClass.name == it }
        val strong = clsHit || txt.contains("已完成") || txt.contains("金币") || txt.contains("红包") || txt.contains("领取") ||
            cd.contains("已完成") || cd.contains("金币") || cd.contains("红包") || cd.contains("领取") ||
            idName.contains("gold", true) || idName.contains("coin", true) || idName.contains("red_packet", true)
        if (strong && w in 40..320 && h in 40..320) {
            v.visibility = View.GONE
            Logger.d("gold HIDDEN strong cls=${v.javaClass.simpleName} w=$w h=$h t=${txt.take(10)}")
            return
        }
        if (txt.isBlank() && cd.isBlank() && w in 40..240 && h in 40..320) {
            v.visibility = View.GONE
            Logger.d("gold HIDDEN plain cls=${v.javaClass.simpleName} w=$w h=$h")
        }
    }

    private fun collectText(v: View): String {
        val sb = StringBuilder()
        fun rec(x: View) {
            if (x is TextView) x.text?.let { sb.append(it) }
            if (sb.length > 30) return
            if (x is ViewGroup) for (i in 0 until x.childCount) rec(x.getChildAt(i) ?: return)
        }
        rec(v)
        return sb.toString().trim()
    }
}
