package com.manjiao.assistant.hook

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import com.manjiao.assistant.KsClass
import com.manjiao.assistant.data.Prefs
import com.manjiao.assistant.util.Logger
import com.manjiao.assistant.util.Reflect
import io.github.libxposed.api.XposedInterface

object ImmersiveHook {

    private var dumped = false
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var tracked: Activity? = null
    @Volatile private var active = false
    private val HIDDEN_TAG = Object()
    private val STRIP_TAG = Object()
    private val GOLD_FAST_IDS = arrayOf("photo_detail_placeholder_lottie", "photo_detail_gold_coin", "gold_coin_pendant", "coin_float_container")
    private var passCount = 0
    private var skipGc = 0
    private val RESET_INTERVAL = 3

    private val HIDE_IDS = arrayOf(
        "bottom_bar_and_grey_cover_container", "bottom_bar", "main_tab_container",
        "comment_container", "comment_editor_container",
        "ai_text_container", "milano_player_seekbar", "nasa_milano_progress_container",
        "speed_anim_container", "pad_slide_auto_play_icon",
        "title_root", "title_mask", "home_fragment_vip", "home_tab_bg", "block_tab_bg",
        "block_float_tabs_mask", "left_btn_parent", "live_btn", "home_share_opened_tip_view",
        "right_action_group", "side_progress_group", "slide_play_like_image",
        "like_button", "comment_button", "collect_button", "forward_button",
        "music_wheel", "slide_play_right_follow", "follow_button",
        "group_right_action_bar_root_layout"
    )

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        val targets = mutableSetOf(
            KsClass.PHOTO_DETAIL_ACTIVITY,
            KsClass.PHOTO_DETAIL_ACTIVITY_TABLET,
            "com.yxcorp.gifshow.HomeActivity",
            "com.yxcorp.gifshow.HomeActivityTablet"
        )
        for (a in targets) {
            val c = Reflect.findClass(a, cl) ?: continue
            val m1 = Reflect.findMethod(c, "onResume", 0)
            if (m1 != null) xp.hook(m1).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("imm.$a").intercept { chain ->
                chain.proceed(); try { start(chain.thisObject as Activity) } catch (_: Throwable) {}; null
            }
            val mOff = Reflect.findMethod(c, "onPause", 0)
            if (mOff != null) xp.hook(mOff).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("imm.off.$a").intercept { chain ->
                chain.proceed(); try { stop(chain.thisObject as Activity) } catch (_: Throwable) {}; null
            }
            Logger.d("ImmersiveHook on $a m1=${m1 != null} off=${mOff != null}")
        }
    }


    private fun start(act: Activity) {
        active = true
        tracked = act
        val immOn = Prefs.bool(Prefs.K_IMM_ON, false)
        val immCustom = Prefs.bool(Prefs.K_IMM_CUSTOM, false)
        Logger.safe("immersive") {
            // 只在一键沉浸/自定义隐藏开启时才隐藏系统栏，避免开关全关时顶栏被误藏
            if (immOn || immCustom || anyCustomSubOn()) hideSystemUi(act)
            val decor = act.window.decorView
            if (!dumped) {
                dumped = true
                decor.postDelayed({ dumpUi(act) }, 1200)
            }
            Logger.safe("vto") {
                val vto = decor.viewTreeObserver
                vto.addOnScrollChangedListener(scrollListener)
                vto.addOnGlobalLayoutListener(layoutListener)
            }
        }
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 100)
    }

    private fun stop(act: Activity) {
        if (tracked === act) {
            active = false
            tracked = null
            handler.removeCallbacks(hideTask)
            handler.removeCallbacks(quickHide)
            Logger.safe("vtoOff") {
                val decor = act.window.decorView
                val vto = decor.viewTreeObserver
                vto.removeOnScrollChangedListener(scrollListener)
                vto.removeOnGlobalLayoutListener(layoutListener)
            }
        }
    }

    private val quickHide = Runnable {
        val act = tracked
        if (act != null && active) Logger.safe("immQuick") { hideByConfig(act) }
    }

    private fun scheduleQuickHide() {
        if (onlyGoldOn()) return
        handler.removeCallbacks(quickHide)
        handler.postDelayed(quickHide, 250)
    }

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { scheduleQuickHide() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scheduleQuickHide() }

    private val hideTask = object : Runnable {
        override fun run() {
            val act = tracked
            if (act != null) hideByConfig(act)
            // 全关时降频轮询（只读 Prefs 判断 mode，零遍历）；有开关开启才高频跑
            if (active) handler.postDelayed(this, if (lastMode == 0) 3000 else if (onlyGoldOn()) 5000 else 2500)
        }
    }

    private fun hideSystemUi(act: Activity) {
        val w = act.window
        if (Build.VERSION.SDK_INT >= 30) {
            w.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
    }

    private var dumpRemain = 0
    @Volatile private var lastMode = 0
    @Volatile private var lastSig = 0
    @Volatile private var firstRestore = true

    private fun anyCustomSubOn() = Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false) || Prefs.bool(Prefs.K_IMM_RIGHT_ON, false)
        || Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false) || Prefs.bool(Prefs.K_IMM_NICKNAME, false)
        || Prefs.bool(Prefs.K_IMM_COLLECTION, false) || Prefs.bool(Prefs.K_IMM_GOLD, false)

    private fun onlyGoldOn() = Prefs.bool(Prefs.K_IMM_GOLD, false)
        && !Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false)
        && !Prefs.bool(Prefs.K_IMM_RIGHT_ON, false)
        && !Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false)
        && !Prefs.bool(Prefs.K_IMM_NICKNAME, false)
        && !Prefs.bool(Prefs.K_IMM_COLLECTION, false)
        && !Prefs.bool(Prefs.K_IMM_ON, false)
        && !Prefs.bool(Prefs.K_IMM_CUSTOM, false)

    private fun hideByConfig(act: Activity) {
        // GC 压力保护：堆 >90% 时跳过本轮遍历，避免在 GC 期间加重主线程负担
        // （快手稳态堆 234/258MB，GC 每次回收 17-64MB 耗时 128-999ms）
        val rt = Runtime.getRuntime()
        if ((rt.totalMemory() - rt.freeMemory()).toFloat() / rt.maxMemory() > 0.9f) {
            skipGc++
            if (skipGc % 10 == 0) Logger.d("imm skip gc heap=${(rt.totalMemory() - rt.freeMemory()) / 1024 / 1024}MB/${rt.maxMemory() / 1024 / 1024}MB n=$skipGc")
            return
        }
        val decor = act.window.decorView as? ViewGroup ?: return
        val immOn = Prefs.bool(Prefs.K_IMM_ON, false)
        val immCustom = Prefs.bool(Prefs.K_IMM_CUSTOM, false)
        val mode = if (immOn) 1 else if (immCustom || anyCustomSubOn()) 2 else 0
        if (mode == 0) {
            // 只在从开→关的转换时做一次全树恢复；之后零遍历（卡顿修复：
            // 之前每 1.5s 全树 walkAll 一遍刷日志）
            // firstRestore：新进程首次无条件恢复一次，清理之前进程残留的隐藏标记
            // （如 kcube_tab_strip willNotDraw=true 导致顶栏文字不显示）
            // 定期 restoreStrip：kcube_tab_strip 可能在首次 restore 后才创建，
            // 需持续恢复其 willNotDraw=false，否则顶栏标签文字不显示
            passCount++
            if (firstRestore || lastMode != 0) {
                restoreAll(decor)
                restoreStrip(decor)
                lastMode = 0
                firstRestore = false
                Logger.d("imm off detected, restored")
            } else if (passCount % 5 == 0) {
                restoreStrip(decor)
                Logger.d("imm off periodic restoreStrip pass=$passCount")
            }
            return
        }
        if (mode != lastMode) {
            restoreAll(decor)
            restoreStrip(decor)
            lastMode = mode
            lastSig = 0
            Logger.d("imm mode changed to $mode, restored")
        }
        val hide = Prefs.strSet(Prefs.K_IMM_HIDE)
        val w = decor.width; val h = decor.height
        if (w == 0 || h == 0) return
        if (!hasVideoPlaying(decor)) {
            // 无视频（图片页/黑屏页）也要恢复：开关关闭后残留的隐藏必须在此还原，
            // 否则一旦离开视频页，hideByConfig 永远提前 return，顶栏等隐藏项无法恢复
            if (mode == 2) {
                val s = buildCustomSets()
                if (s.top.isEmpty() && s.right.isEmpty() && s.other.isEmpty()) {
                    restoreAll(decor)
                } else {
                    restoreUnmatched(decor, s.top, s.right, s.other, w, h)
                    if (s.top.isEmpty()) restoreStrip(decor)
                }
            }
            return
        }

        passCount++
        val force = passCount % RESET_INTERVAL == 0


        var c1 = 0
        var c2 = 0
        if (immOn) {
            c1 = hideByIds(decor, force)
            c2 = hideByPosition(decor, w, h, force)
            // 一键沉浸同时应用自定义隐藏子项（topbar/right/other），
            // doRestore=false 避免 restoreUnmatched 把一键沉浸 hideByIds/hideByPosition
            // 隐藏的 view 恢复成 VISIBLE 导致右侧按钮闪烁
            val sets = buildCustomSets()
            if (sets.top.isNotEmpty() || sets.right.isNotEmpty() || sets.other.isNotEmpty()) {
                val c3 = hideSelected(decor, sets.top, sets.right, sets.other, w, h, doRestore = false)
                c2 += c3
            }
            Logger.d("imm pass=$passCount force=$force ids=$c1 pos=$c2")
        } else {
            if (onlyGoldOn()) {
                val actRef = act
                for (gn in GOLD_FAST_IDS) {
                    val gid = actRef.resources.getIdentifier(gn, "id", "com.smile.gifmaker")
                    if (gid != 0) {
                        val gv = actRef.findViewById(gid) as? View
                        if (gv != null && gv.visibility != View.GONE) { gv.visibility = View.GONE; c2++ }
                    }
                }
            } else {
                val sets = buildCustomSets()
                val topbarSet = sets.top
                val rightSet = sets.right
                val otherSet = sets.other

                c2 = hideSelected(decor, topbarSet, rightSet, otherSet, w, h)
                if (topbarSet.isNotEmpty()) c2 += hideTopBarIndicator(decor, w, h)
                else restoreStrip(decor)
                if (passCount % 20 == 0) Logger.d("imm dbg topbar=${topbarSet.size} right=${rightSet.size} other=${otherSet.size} items=${topbarSet.joinToString(",")}")
                if (passCount % 20 == 0) Logger.d("imm pass=$passCount force=$force ids=$c1 sel=$c2")
            }
            // dumpTopBar 每30轮刷屏拖垮 logcat/CPU，已停用
        }
        // dumpRemaining 诊断已完成使命（每轮50条刷爆 logcat 256KB 缓冲，冲掉其他模块
        // 日志），永久停用；需要时临时恢复此调用
        // if (dumpRemain < 15) { dumpRemain++; dumpRemaining(decor, w, h) }
    }

    private fun clearTags(root: ViewGroup) {
        walkAll(root) { v ->
            if (v.tag === HIDDEN_TAG) {
                v.tag = null
                if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
            } else if (v.tag === STRIP_TAG) {
                v.tag = null
                v.setWillNotDraw(false)
                v.invalidate()
            }
        }
    }

    private fun restoreAll(root: ViewGroup) {
        var n = 0
        walkAll(root) { v ->
            if (v.tag === HIDDEN_TAG) {
                v.tag = null
                if (v.visibility != View.VISIBLE) { v.visibility = View.VISIBLE; n++ }
            } else if (v.tag === STRIP_TAG) {
                v.tag = null
                v.setWillNotDraw(false)
                v.invalidate()
                n++
            }
        }
        if (n > 0) Logger.d("imm restored $n views")
    }

    private fun dumpRemaining(root: ViewGroup, w: Int, h: Int) {
        var n = 0
        walk(root) { v ->
            if (n >= 50) return@walk
            if (v === root) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            if (v.width == 0 || v.height == 0) return@walk
            val left = v.left; val top = v.top; val right = v.right; val bottom = v.bottom
            val isRight = left > w * 45 / 100
            val isBottom = bottom > h * 65 / 100
            val isLeftMid = left < w * 40 / 100 && top in (h * 10 / 100)..(h * 75 / 100)
            if (!isRight && !isBottom && !isLeftMid) return@walk
            val cn = v.javaClass.simpleName
            val idName = idNameOf(v)
            val b = "[$left,$top][$right,$bottom]"
            val t = if (v is TextView) v.text?.toString()?.take(15) ?: "" else ""
            Logger.d("imm remain $cn $b${if (idName.isNotEmpty()) " id=$idName" else ""}${if (t.isNotEmpty()) " t=$t" else ""}")
            n++
        }
    }

    private fun restoreStrip(root: ViewGroup) {
        // 按 id 无条件恢复：kcube_tab_strip 的 tag 是公共属性，会被快手 CubeUI
        // 代码覆盖，依赖 STRIP_TAG 找回会造成文字永久消失（只剩图标）
        walkAll(root) { v ->
            if (v.id != View.NO_ID) {
                val idName = idNameOf(v)
                if (idName == "kcube_tab_strip") {

                    v.tag = null
                    v.setWillNotDraw(false)
                    v.invalidate()
                    // 恢复子 view visibility（TabStripContainerLayout 的标签子项可能被 GONE）
                    if (v is ViewGroup) {
                        var restored = 0
                        for (i in 0 until v.childCount) {
                            val child = v.getChildAt(i) ?: continue
                            if (child.visibility != View.VISIBLE) { child.visibility = View.VISIBLE; restored++ }
                            if (child is ViewGroup) {
                                for (j in 0 until child.childCount) {
                                    val gc = child.getChildAt(j) ?: continue
                                    if (gc.visibility != View.VISIBLE) { gc.visibility = View.VISIBLE; restored++ }
                                }
                            }
                        }
                        if (restored > 0) Logger.d("imm STRIP restored $restored children vis")
                    }
                    Logger.d("imm STRIP restored willNotDraw=false")
                    return@walkAll
                }
            }
            if (v.tag === STRIP_TAG) {
                v.tag = null
                v.setWillNotDraw(false)
                v.invalidate()
            }
        }
    }

    private fun hideTopBarIndicator(root: ViewGroup, w: Int, h: Int): Int {
        var count = 0
        val loc = IntArray(2)
        val debug = passCount % 30 == 0
        if (debug) Logger.d("imm line dbg start")
        walkAll(root) { v ->
            if (v === root) return@walkAll
            if (v.visibility != View.VISIBLE) return@walkAll
            if (v.width == 0 || v.height == 0) return@walkAll
            v.getLocationOnScreen(loc)
            val absY = loc[1]; val absX = loc[0]
            if (absY >= h / 4) return@walkAll
            val idName = idNameOf(v)
            if (idName == "kcube_tab_strip" && v.tag !== STRIP_TAG) {
                v.setWillNotDraw(true)
                v.tag = STRIP_TAG
                v.invalidate()
                count++
                Logger.d("imm STRIP willNotDraw y=$absY x=$absX w=${v.width}")
            }
            val cn = v.javaClass.simpleName
            val isIndicator = idName.contains("indicator", true) || idName.contains("underline", true) || idName.contains("selector_line", true) || idName.contains("tab_line", true) || idName.contains("tab_indicator", true) || cn.contains("Indicator", true)
            // 指示器实际高度常含 padding 超 10px，放宽到 16 并限定顶栏 1/8 区域内的细长条
            val isLine = (v.height in 1..10 && v.width > 20) ||
                (v.height in 1..16 && v.width in 20..(w / 3) && absY < h / 8 && v !is TextView && idName != "kcube_tab_strip")
            if (isIndicator || isLine) {
                v.visibility = View.GONE
                v.tag = HIDDEN_TAG
                count++
                Logger.d("imm LINE hidden y=$absY h=${v.height} w=${v.width} id=$idName cls=$cn")
            }
        }
        if (debug) Logger.d("imm line dbg end")
        return count
    }

    private fun dumpTopBar(root: ViewGroup, w: Int, h: Int) {
        var n = 0
        val loc = IntArray(2)
        Logger.d("imm topbar dump start w=$w h=$h")
        walkAll(root) { v ->
            if (n >= 400) return@walkAll
            if (v === root) return@walkAll
            if (v.width == 0 || v.height == 0) return@walkAll
            v.getLocationOnScreen(loc)
            val absY = loc[1]; val absX = loc[0]
            val t = if (v is TextView) v.text?.toString() ?: "" else ""
            val cd = v.contentDescription?.toString() ?: ""
            val idName = idNameOf(v)
            if (t.isBlank() && cd.isBlank() && idName.isBlank()) return@walkAll
            val ft = fixStr(t); val fcd = fixStr(cd)
            Logger.d("imm tb y=$absY x=$absX vis=${v.visibility} cls=${v.javaClass.simpleName} w=${v.width} h=${v.height}${if (idName.isNotEmpty()) " id=$idName" else ""}${if (ft.isNotEmpty()) " t=$ft" else ""}${if (fcd.isNotEmpty()) " cd=$fcd" else ""}${if (v is ViewGroup) " kids=${v.childCount}" else ""}")
            n++
        }
        Logger.d("imm topbar dump end n=$n")
    }

    private fun hasVideoPlaying(root: View): Boolean {
        if (root.visibility != View.VISIBLE) return false
        if (root is SurfaceView || root is TextureView) return root.width > 100 && root.height > 100
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (hasVideoPlaying(root.getChildAt(i) ?: continue)) return true
            }
        }
        return false
    }

    private val idNameCache = android.util.SparseArray<String>()

    /** id->资源名缓存：每轮全树遍历数百 view 的 getResourceEntryName 是主线程最大开销（缓存后 int 查表） */
    private fun idNameOf(v: View): String {
        val rid = v.id
        if (rid == View.NO_ID) return ""
        val c = idNameCache.get(rid)
        if (c != null) return c
        val n = try { v.resources.getResourceEntryName(rid) } catch (_: Throwable) { "" }
        idNameCache.put(rid, n)
        return n
    }
    private fun hideByIds(root: ViewGroup, force: Boolean): Int {
        var count = 0
        walk(root) { v ->
            if (v === root) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            val idName = idNameOf(v)
            for (key in HIDE_IDS) {
                if (idName == key || idName.contains(key, true)) {
                    if (hide(v, force)) count++; break
                }
            }
        }
        return count
    }

    private fun hideByPosition(root: ViewGroup, w: Int, h: Int, force: Boolean): Int {
        var count = 0
        walk(root) { v ->
            if (v === root) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            val vw = v.width; val vh = v.height
            if (vw == 0 || vh == 0) return@walk
            val left = v.left; val top = v.top; val right = v.right; val bottom = v.bottom
            val isFloating = vw in 40..250 && vh in 40..250 && (right > w * 60 / 100 && bottom > h * 60 / 100 || left < w * 40 / 100 && bottom > h * 60 / 100)
            val isLeftAuthor = left < w * 35 / 100 && top in (h * 10 / 100)..(h * 70 / 100) && vw in 30..300 && vh in 30..300
            if (isFloating || isLeftAuthor) {
                if (hide(v, force)) count++; return@walk
            }
            if (v !is ViewGroup) return@walk
            if (v.childCount == 0) return@walk
            val isRightCol = left > w * 82 / 100 && vw in 1..(w / 5) && vh in 1..(h * 2 / 3) && bottom > h / 3
            val isTopBar = top in 0..(h / 4) && vh in 1..(h / 3) && vw > w / 2
            val isBottomBar = bottom > h * 3 / 4 && vh in 1..(h / 3) && vw > w / 2
            if (isRightCol || isTopBar || isBottomBar) {
                if (hide(v, force)) count++
            }
        }
        return count
    }

    private fun hideAllUi(root: ViewGroup, w: Int, h: Int, force: Boolean): Int {
        var count = 0
        walk(root) { v ->
            if (v === root) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            val vw = v.width; val vh = v.height
            if (vw == 0 || vh == 0) return@walk
            if (vw > w / 2 && vh > h / 2) return@walk
            val text = if (v is TextView) v.text?.toString() else null
            val cd = v.contentDescription?.toString()
            val hasUi = (text != null && text.isNotBlank()) || (cd != null && cd.isNotBlank())
            if (hasUi) {
                if (hide(v, force)) count++
            }
        }
        return count
    }

    private val RIGHT_ID_MAP = linkedMapOf(
        "like_button" to "喜欢",
        "comment_button" to "评论",
        "collect_button" to "收藏",
        "forward_button" to "转发",
        "music_wheel" to "音乐封面",
        "slide_play_right_follow" to "关注",
        "follow_button" to "关注"
    )

    private class MatchResult(val item: String, val isRight: Boolean, val byIdRight: Boolean, val absX: Int, val absY: Int, val isTopArea: Boolean, val isRightArea: Boolean)

    private val NICK_CAPTION_IDS = setOf("username_group", "user_name_text_view", "caption_scroll_container", "element_caption_label", "global_caption_label", "slide_play_caption_root")

    private fun matchHideItem(v: View, topbar: Set<String>, right: Set<String>, other: Set<String>, w: Int, h: Int, loc: IntArray): MatchResult? {
        if (v.width == 0 || v.height == 0) return null
        val text = if (v is TextView) v.text?.toString() else null
        val cd = v.contentDescription?.toString()
        val t = text ?: fixStr(cd ?: "")
        val idName = idNameOf(v)
        v.getLocationOnScreen(loc)
        val absX = loc[0]; val absY = loc[1]
        if (absY >= h || absY + v.height <= 0) return null
        if (absX + v.width <= 0 || absX >= w) return null
        val isTopArea = absY < h / 5
        val isRightArea = absX > w * 2 / 3 && absY in (h / 5)..(h * 95 / 100)
        val isNickOrCaption = idName in NICK_CAPTION_IDS
        if (idName.isNotEmpty()) {
            for ((key, item) in RIGHT_ID_MAP) {
                if (idName == key || idName.contains(key)) {
                    if (item in right) return MatchResult(item, true, true, absX, absY, isTopArea, isRightArea)
                    if (absX > w * 4 / 5) return null
                }
            }
        }
        if (isTopArea) {
            for (item in topbar) {
                if (matchItem(item, v, t, w, h)) return MatchResult(item, false, false, absX, absY, isTopArea, isRightArea)
            }
        }
        if (isRightArea) {
            for (item in right) {
                if (matchItem(item, v, t, w, h)) return MatchResult(item, true, false, absX, absY, isTopArea, isRightArea)
            }
        }
        for (item in other) {
            val isLeftMid = absX < w / 2 && absY in (h / 5)..(h * 95 / 100)
            val isBottom = absY > h * 4 / 5
            if (item == "合集" && (isNickOrCaption || t.length > 20)) continue
            val posOk = when (item) {
                "作者昵称", "视频文案" -> isLeftMid
                "合集" -> absY in (h * 7 / 10)..(h * 95 / 100)
                "底部Tab栏" -> isBottom && absX < w / 4
                else -> true
            }
            if (posOk && matchItem(item, v, t, w, h)) return MatchResult(item, false, false, absX, absY, isTopArea, isRightArea)
        }
        if ("合集" in other && !isNickOrCaption && idName.isEmpty() && v !is TextView && v.width > w / 2 && v.height in 1..10 && absY in (h * 7 / 10)..(h * 98 / 100) && absX < w * 4 / 5) {
            return MatchResult("合集", false, false, absX, absY, isTopArea, isRightArea)
        }
        if ("金币红包" in other && idName.isEmpty() && v !is TextView && v !is SurfaceView && v !is TextureView) {
            val inFloatArea = absY > h / 5 && absY + v.height < h * 9 / 10 && absX > w / 10 && absX + v.width < w * 9 / 10
            val childOk = v !is ViewGroup || v.childCount in 1..4
            val plainSquare = t.isBlank() && v.width in 40..200 && v.height in 40..200 && Math.abs(v.width - v.height) * 3 < v.width
            val pendantShape = v.width in 40..200 && v.height in 40..320 && v.height > v.width
            if (inFloatArea && childOk && (plainSquare || pendantShape)) {
                return MatchResult("金币红包", false, false, absX, absY, isTopArea, isRightArea)
            }
        }
        return null
    }

    private class CustomSets(val top: Set<String>, val right: Set<String>, val other: Set<String>) {
        val any: Boolean get() = top.isNotEmpty() || right.isNotEmpty() || other.isNotEmpty()
    }

    @Volatile private var setsCache: CustomSets? = null
    @Volatile private var setsCacheSig = -1

    /** 结果缓存：hideByConfig 每轮（250ms~1.5s）重建 sets+打日志是重复开销；sig 含开关值与集合 hash，变化才重建 */
    private fun buildCustomSets(): CustomSets {
        val topOn = Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false)
        val rightOn = Prefs.bool(Prefs.K_IMM_RIGHT_ON, false)
        val nickOn = Prefs.bool(Prefs.K_IMM_NICKNAME, false)
        val collOn = Prefs.bool(Prefs.K_IMM_COLLECTION, false)
        val botOn = Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false)
        val goldOn = Prefs.bool(Prefs.K_IMM_GOLD, false)
        val top = if (topOn) Prefs.strSet(Prefs.K_IMM_TOPBAR).toSet() else emptySet()
        val right = if (rightOn) Prefs.strSet(Prefs.K_IMM_RIGHT_ITEMS).toSet() else emptySet()
        val sig = (if (topOn) 1 else 0) + (if (rightOn) 2 else 0) + (if (nickOn) 4 else 0) +
            (if (collOn) 8 else 0) + (if (botOn) 16 else 0) + (if (goldOn) 32 else 0) +
            top.hashCode() * 31 + right.hashCode()
        val c = setsCache
        if (c != null && setsCacheSig == sig) return c
        val other = mutableSetOf<String>()
        if (nickOn) { other.add("作者昵称"); other.add("视频文案") }
        if (collOn) other.add("合集")
        if (botOn) other.add("底部Tab栏")
        if (goldOn) other.add("金币红包")
        val s = CustomSets(top, right, other)
        setsCache = s; setsCacheSig = sig
        Logger.d("imm sets top=$top right=$right other=$other gold=$goldOn")
        return s
    }

    /**
     * 恢复段：把带 HIDDEN_TAG 且已不匹配任何隐藏集合的视图还原（开关关闭后调用）
     */
    private fun restoreUnmatched(root: ViewGroup, topbar: Set<String>, right: Set<String>, other: Set<String>, w: Int, h: Int): Int {
        var restored = 0
        val loc = IntArray(2)
        walkAll(root) { v ->
            if (v === root) return@walkAll
            if (v.tag !== HIDDEN_TAG) return@walkAll
            if (v.visibility == View.VISIBLE) {
                if (v.width > 0 && v.height > 0) {
                    val m = try { matchHideItem(v, topbar, right, other, w, h, loc) } catch (_: Throwable) { null }
                    if (m != null) { v.visibility = View.GONE; return@walkAll }
                }
                v.tag = null; return@walkAll
            }
            // GONE 的 view 宽高为 0，matchHideItem 必返回 null，
            // 若据此恢复会造成"隐藏→恢复→再隐藏"循环闪烁，故尺寸为 0 时保守不恢复
            if (v.width == 0 || v.height == 0) return@walkAll
            try {
                v.getLocationOnScreen(loc)
                if (loc[0] + v.width <= 0 || loc[0] >= w) return@walkAll
            } catch (_: Throwable) { return@walkAll }
            val m = try { matchHideItem(v, topbar, right, other, w, h, loc) } catch (_: Throwable) { null }
            if (m == null) {
                v.visibility = View.VISIBLE
                v.tag = null
                restored++
                Logger.d("imm restore cls=${v.javaClass.simpleName} w=${v.width} h=${v.height}")
            }
        }
        if (restored > 0) Logger.d("imm restored $restored views")
        return restored
    }

    private fun hideSelected(root: ViewGroup, topbar: Set<String>, right: Set<String>, other: Set<String>, w: Int, h: Int, doRestore: Boolean = true): Int {
        var count = 0
        val loc = IntArray(2)
        val goldDebug = "金币红包" in other && passCount % 10 == 0
        if (goldDebug) Logger.d("gold dbg begin")
        if (doRestore) restoreUnmatched(root, topbar, right, other, w, h)
        walk(root) { v ->
            if (v === root) return@walk
            if (v.visibility != View.VISIBLE) return@walk
            if (isVideoView(v)) return@walk
            val m = try { matchHideItem(v, topbar, right, other, w, h, loc) } catch (_: Throwable) { null }
            if (m != null) {
                Logger.d("imm M item=${m.item} cls=${v.javaClass.simpleName} id=$<IDNAMEOF> x=${m.absX} y=${m.absY} w=${v.width} h=${v.height}")
                if (m.byIdRight) {
                    v.visibility = View.GONE; v.tag = HIDDEN_TAG; count++
                } else if (hideTopItem(v, root, w, h, m.isRight || m.isRightArea, m.absY)) {
                    count++
                    if (m.isTopArea) hideIndicatorNear(v, root)
                }
            } else if (goldDebug && v.width in 40..200 && v.height in 40..200) {
                val txt = (v as? TextView)?.text?.toString() ?: ""
                val cdsc = v.contentDescription?.toString() ?: ""
                val idN = idNameOf(v)
                if (txt.isBlank() && cdsc.isBlank()) {
                    v.getLocationOnScreen(loc)
                    Logger.d("gold cand cls=${v.javaClass.simpleName} id=$idN x=${loc[0]} y=${loc[1]} w=${v.width} h=${v.height} kids=${if (v is ViewGroup) v.childCount else 0}")
                }
            }
        }
        return count
    }

    private fun hideTopItem(v: View, root: ViewGroup, w: Int, h: Int, isRight: Boolean = false, absY: Int = 0): Boolean {
        val p = v.parent
        if (absY < h / 5 && p is ViewGroup && p !== root && p.left <= v.left && p.right >= v.right && p.top <= v.top && p.bottom >= v.bottom && p.width < w / 2 && p.height < h / 4) {
            if (p.visibility == View.VISIBLE) {
                p.visibility = View.GONE
                p.tag = HIDDEN_TAG
                if (v.visibility == View.VISIBLE) { v.visibility = View.GONE; v.tag = HIDDEN_TAG }
                return true
            }
            return false
        }
        if (v.visibility == View.VISIBLE && v.width <= w && v.height < h / 2) { v.visibility = View.GONE; v.tag = HIDDEN_TAG; return true }
        return false
    }

    private fun hideIndicatorNear(tab: View, root: ViewGroup) {
        val tabLeft = tab.left; val tabRight = tab.right; val tabBottom = tab.bottom
        val h = root.height
        walkAll(root) { v ->
            if (v === tab) return@walkAll
            if (v.visibility != View.VISIBLE) return@walkAll
            if (v.width == 0 || v.height == 0) return@walkAll
            // 仅细条(≤10px)才算 tab 指示器：isSelected 的大控件是个人页"作品/喜欢"等
            // 选中项（h=51/19），h<80 会误藏个人页顶部 tab
            if (v.isSelected && v.height <= 10 && v.width < root.width / 2) {
                var absY = 0; var p: View? = v
                while (p != null && p !== root) { absY += p.top; p = (p as? View)?.parent as? View }
                if (absY < h / 5) {
                    Logger.d("imm IND sel hidden cls=${v.javaClass.simpleName} absY=$absY left=${v.left} w=${v.width} h=${v.height}")
                    v.visibility = View.GONE
                    v.tag = HIDDEN_TAG
                }
            }
            val near = v.top in (tabBottom - 5)..(tabBottom + 80)
            if (near && v.height <= 30) {
                val overlap = v.left < tabRight && v.right > tabLeft
                if (overlap && v.height <= 10) {
                    Logger.d("imm IND hidden h=${v.height} w=${v.width} top=${v.top} cls=${v.javaClass.simpleName}")
                    hide(v, true)
                }
            }
        }
    }

    private fun isVideoView(v: View): Boolean {
        val cn = v.javaClass.name
        return v is SurfaceView || v is TextureView || cn.contains("VideoView") || (cn.contains("Player") && !cn.contains("Kit"))
    }

    private fun fixStr(s: String): String {
        if (s.isEmpty()) return s
        var hasHi = false
        for (c in s) { if (c.code in 0x80..0xFF) { hasHi = true; break } }
        if (!hasHi) return s
        return try { String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) } catch (_: Throwable) { s }
    }

    private val LEAF_ITEMS = setOf(
        "精选","看游戏","玩游戏","短剧","同城","关注","发现","直播",
        "养萌宠","王者送福利","侧边栏","搜索",
        "点赞","评论","分享","收藏","喜欢","音乐唱片"
    )

    private fun matchItem(item: String, v: View, t: String, w: Int, h: Int): Boolean {
        val cn = v.javaClass.name
        val idName = idNameOf(v)
        if (item in LEAF_ITEMS && v is ViewGroup && v.width > w * 3 / 5) return false
        return when (item) {
            "点赞" -> t.contains("点赞") || t.contains("like", true)
            "评论" -> t.contains("评论") || t.contains("comment", true)
            "分享" -> t.contains("分享") || t.contains("share", true)
            "收藏" -> t.contains("收藏") || t.contains("favorite", true) || t.contains("fav", true)
            "关注" -> t.contains("关注") || t.contains("follow", true) || idName.contains("follow", true)
            "喜欢" -> t.contains("喜欢") || t.contains("like", true) || idName.contains("like", true)
            "作者头像" -> cn.contains("Avatar", true) || t.contains("头像")
            "作者昵称" -> idName == "user_name_text_view" || idName.contains("username_group", true) || idName.contains("verify", true) || idName.contains("disclaimer") || idName.contains("reco_reason") || idName.contains("tube_panel") || idName.contains("tube_first") || idName.contains("tube_second") || idName.contains("tube_third") || t == "短剧"
            "视频文案" -> idName == "element_caption_label" || idName == "global_caption_label" || (t.length > 20 && cn.contains("TextView"))
            "顶部栏" -> v is ViewGroup && v.top in 0..(h / 6) && v.height in 1..(h / 10) && v.width > w / 2 && v.childCount > 1
            "底部Tab栏" -> idName.contains("bottom_bar") || idName.contains("bottom_navigation") || idName.contains("main_tab") || (v !is TextView && v.width > w * 9 / 10 && v.height in 1..(h / 8) && v.bottom > h * 5 / 6)
            "音乐旋转" -> cn.contains("Music", true) || t.contains("音乐")
            "音乐唱片" -> cn.contains("Music", true) || t.contains("音乐") || idName.contains("music", true)
            "音乐封面" -> cn.contains("Music", true) || t.contains("音乐") || idName.contains("music", true) || idName.contains("album", true)
            "转发" -> t.contains("转发") || t.contains("forward", true) || idName.contains("forward", true) || idName.contains("share", true)
            "倒计时" -> t.contains("倒计时") || cn.contains("Timer", true)
            "进度条" -> cn.contains("SeekBar", true) || cn.contains("ProgressBar", true)
            "倍速" -> t.contains("倍速") || t.contains("speed", true)
            "清晰度" -> t.contains("清晰度") || t.contains("quality", true) || t.contains("画质")
            "搜索" -> t.contains("搜索") || t.contains("search", true) || idName.contains("search", true)
            "侧边栏" -> t.contains("侧边栏") || idName.contains("sidebar", true) || idName.contains("drawer", true)
            // avatar/head 类 id 需限定小尺寸(宽<屏宽1/6)：首页顶栏头像是小图标(~51px)，
            // 个人页账号头像是大图(~112-120px)，不限尺寸会误藏个人页账号信息
            "左上角按钮" -> idName.contains("sidebar", true) || idName.contains("drawer", true) || (v.width < w / 4 && idName.contains("menu", true)) || (v.width < w / 6 && (idName.contains("avatar", true) || idName.contains("head", true))) || (t.contains("侧边栏"))
            "游戏" -> t.contains("看游戏") || t.contains("玩游戏") || t == "游戏" || t == "游戏TV"
            "看游戏" -> t.contains("看游戏") || t == "游戏" || t == "游戏TV"
            "玩游戏" -> t.contains("玩游戏") || t == "游戏" || t == "游戏TV"
            "短剧" -> t.contains("短剧") || idName.contains("drama", true)
            "同城" -> t.contains("同城") || idName.contains("local", true) || idName.contains("city", true)
            "直播" -> t.contains("直播") || t.contains("live", true) || idName.contains("live", true)
            "发现" -> t.contains("发现") || t.contains("discover", true) || idName.contains("discover", true)
            "精选" -> t.contains("精选") || t.contains("feature", true) || idName.contains("feature", true) || idName.contains("select", true)
            "精选指示器" -> idName.contains("indicator", true) || idName.contains("underline", true) || idName.contains("selector_line", true)
            "养萌宠" -> t.contains("养萌宠") || t.contains("萌宠") || t.contains("pet", true)
            "王者送福利" -> t.contains("王者送福利") || t.contains("王者") || t.contains("福利")
            "合集" -> t.contains("合集") || t.contains("上一集") || t.contains("下一集") || t.contains("看全集") || t.contains("热榜") || t.contains("高光") || t.contains("完整版") || t.contains("文娱榜") || t.contains("万人在看") || t.contains("播放量") || (t.contains("全") && t.contains("集")) || idName.contains("collection", true) || idName.contains("feed_set", true) || idName.contains("chapter", true) || idName.contains("serial", true) || idName.contains("tube_panel", true) || idName.contains("tube_first", true) || idName.contains("tube_second", true) || idName.contains("tube_third", true) || idName.contains("tube_tk_action", true) || idName.contains("general_entry", true) || idName.contains("group_bottom_root", true) || idName == "bottom_shadow"
            "金币红包" -> t.contains("金币") || t.contains("红包") || idName.contains("gold", true) || idName.contains("coin", true) || idName.contains("red_packet", true) || idName.contains("pendant", true) || idName.contains("suspend", true) || idName.contains("float_task", true) || idName.contains("hang_widget", true) || idName.contains("treasure", true) || idName.contains("placeholder_lottie", true) || idName.contains("lottie_anim", true)
            else -> t.contains(item)
        }
    }

    private fun dumpUi(act: Activity) {
        try {
            val decor = act.window.decorView as? ViewGroup ?: return
            val w = decor.width; val h = decor.height
            Logger.d("imm dump start w=$w h=$h pkg=${act.javaClass.simpleName}")
            walk(decor, 0) { v, d ->
                if (v.visibility != View.VISIBLE) return@walk
                val cn = v.javaClass.simpleName
                val idName = idNameOf(v)
                val t = if (v is TextView) v.text?.toString() ?: "" else ""
                val cd = v.contentDescription?.toString() ?: ""
                val b = "[${v.left},${v.top}][${v.right},${v.bottom}]"
                val info = buildString {
                    append("d=$d ").append(cn).append(" ").append(b)
                    if (idName.isNotEmpty()) append(" id=").append(idName)
                    if (t.isNotEmpty()) append(" t=").append(t.take(20))
                    if (cd.isNotEmpty()) append(" cd=").append(cd.take(20))
                    if (v is ViewGroup) append(" children=").append(v.childCount)
                }
                Logger.d("imm $info")
            }
            Logger.d("imm dump end")
        } catch (e: Throwable) {
            Logger.d("imm dump err: ${e.message}")
        }
    }

    private fun walk(v: View, cb: (View) -> Unit) {
        cb(v)
        if (isVideoView(v)) return
        if (v.tag === HIDDEN_TAG && v.visibility == View.VISIBLE) v.visibility = View.GONE
        if (com.manjiao.assistant.ui.MainMenuDialog.isOverlay(v)) return
        if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, cb) }
    }

    private fun walkAll(v: View, cb: (View) -> Unit) {
        cb(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { walkAll(it, cb) }
    }

    private fun walk(v: View, depth: Int, cb: (View, Int) -> Unit) {
        cb(v, depth)
        if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, depth + 1, cb) }
    }

    private fun hide(v: View, force: Boolean): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (!force && v.tag !== HIDDEN_TAG) return false
        if (hasVideoChild(v, 0)) return false
        v.visibility = View.INVISIBLE
        v.tag = HIDDEN_TAG
        return true
    }

    private fun hasVideoChild(v: View, depth: Int): Boolean {
        if (depth >= 4) return false
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i) ?: continue
                if (isVideoView(c)) return true
                if (c is ViewGroup && hasVideoChild(c, depth + 1)) return true
            }
        }
        return false
    }
}
