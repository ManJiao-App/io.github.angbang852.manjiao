package io.github.angbang852.manjiao.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

import android.widget.TextView
import android.widget.Toast
import io.github.angbang852.manjiao.KsClass

import io.github.angbang852.manjiao.data.CurrentVideo
import io.github.angbang852.manjiao.data.DownloadService
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.hook.ContentFilterHook
import io.github.angbang852.manjiao.util.Logger


object MainMenuDialog {

    private val C_PRIMARY = 0xFFFF6600.toInt()

    private var currentOverlay: View? = null
    fun isOverlay(v: View): Boolean = v === currentOverlay

    private class Item(val icon: String, val title: String, val hasSub: Boolean = true, val action: () -> Unit)

    private fun isDark(ctx: Context): Boolean {
        return (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun cTitle(ctx: Context) = Color.WHITE
    private fun cDiv(ctx: Context) = 0x33000000.toInt()
    private fun cArrow(ctx: Context) = Color.WHITE
    private fun cRipple(ctx: Context) = 0x1A000000.toInt()
    private fun cInputBg(ctx: Context) = 0x99FFFFFF.toInt()

    private fun outline(tv: TextView) {
        tv.setTextColor(Color.WHITE)
        tv.setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private fun jellyEnter(view: View) {
        view.scaleX = 0.82f
        view.scaleY = 0.82f
        view.alpha = 0f
        view.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(440)
            .setInterpolator(OvershootInterpolator(2.6f))
            .start()
    }

    fun show(ctx: Context) {
        Logger.d("MainMenuDialog.show called")
        Prefs.reload()
        val items = listOf(
            Item("", "刷新内容", hasSub = false) { ContentFilterHook.refreshContent() },
            Item("", "下载") { showDownload(ctx) },
            Item("", "沉浸式页面") { showImmersive(ctx) },
            Item("", "手势功能") { showGesture(ctx) },
            Item("", "播放控制") { showPlayback(ctx) },
            Item("", "内容过滤") { showFilter(ctx) },
            Item("", "性能优化") { showPerf(ctx) },
            Item("", "快手净化") { showPurify(ctx) },
        )
        showSheet(ctx, "ManJiao", items, null)
    }

    private fun showDownload(ctx: Context) {
        val v = CurrentVideo.current
        val valid = v.valid()
        val items = listOf(
            Item("", "视频下载", hasSub = false) {
                if (!valid) { toast(ctx, "未捕获到视频，请先播放视频"); return@Item }
                val dir = Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH)
                DownloadService.downloadVideo(ctx, v, dir)
            },
            Item("", "音频提取", hasSub = false) {
                if (!valid) { toast(ctx, "未捕获到视频，请先播放视频"); return@Item }
                val dir = Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH)
                DownloadService.downloadAudio(ctx, v, dir)
            },
            Item("", "图集下载", hasSub = false) {
                if (v.isImage) { DownloadService.downloadImages(ctx, v, Prefs.str(Prefs.K_DL_PATH, Prefs.DEFAULT_PATH)) }
                else { toast(ctx, "当前不是图集，请左右滑动到图片集") }
            },
        )
        showSheet(ctx, "下载", items, { show(ctx) })
    }

    private fun showGesture(ctx: Context) {
        Prefs.reload()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(itemSwitchRow(ctx, "", "禁止双击点赞", Prefs.bool(Prefs.K_GS_NO_DBL_LIKE, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_GS_NO_DBL_LIKE, nv)
            toast(ctx, if (nv) "已禁止双击点赞" else "已恢复双击点赞")
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "双击打开评论区", Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_GS_OPEN_COMMENT, nv)
            if (nv && Prefs.bool(Prefs.K_GS_OPEN_MENU, false)) {
                Prefs.setBoolSync(ctx, Prefs.K_GS_OPEN_MENU, false)
                toast(ctx, "已自动关闭「双击打开模块菜单」")
                showGesture(ctx)
            }
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "双击打开模块菜单", Prefs.bool(Prefs.K_GS_OPEN_MENU, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_GS_OPEN_MENU, nv)
            if (nv && Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false)) {
                Prefs.setBoolSync(ctx, Prefs.K_GS_OPEN_COMMENT, false)
                toast(ctx, "已自动关闭「双击打开评论区」")
                showGesture(ctx)
            }
        })
        showSheetScroll(ctx, "手势功能", { show(ctx) }, container)
    }

    private fun showGestureTaps(ctx: Context, key: String, title: String) {
        Prefs.reload()
        val cur = Prefs.int(key, 2)
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (taps in 0..1) {
            val n = if (taps == 0) 2 else 3
            val row = itemSwitchRow(ctx, "", "${n}击", cur == n) { nv ->
                if (nv) Prefs.setIntSync(ctx, key, n)
            }
            container.addView(row)
            container.addView(divider(ctx))
        }
        showSheetScroll(ctx, title, { showGesture(ctx) }, container)
    }

    private fun showPlayback(ctx: Context) {
        Prefs.reload()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(itemSwitchRow(ctx, "", "停止循环播放", Prefs.bool(Prefs.K_PB_NO_LOOP, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_PB_NO_LOOP, nv)
            toast(ctx, if (nv) "已停止循环播放" else "已恢复循环播放")
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "后台暂停播放", Prefs.bool(Prefs.K_PB_BG_PAUSE, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_PB_BG_PAUSE, nv)
            toast(ctx, if (nv) "已开启后台暂停" else "已关闭后台暂停")
        })
        showSheetScroll(ctx, "播放控制", { show(ctx) }, container)
    }

    private fun showImmersive(ctx: Context) {
        Prefs.reload()
        val sw = itemSwitchRow(ctx, "", "一键沉浸", Prefs.bool(Prefs.K_IMM_ON, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_ON, nv)
            toast(ctx, if (nv) "一键沉浸已开启" else "一键沉浸已关闭")
        }
        showSheet(ctx, "沉浸式页面", listOf(Item("", "自定义隐藏") { showHideCustom(ctx) }), { show(ctx) }, sw)
    }


    private fun showHideCustom(ctx: Context) {
        Prefs.reload()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        container.addView(itemSwitchRow(ctx, "", "顶栏", Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false), hasSub = true, subAction = { showTopBarItems(ctx) }) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_TOPBAR_ON, nv)
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "右侧按钮", Prefs.bool(Prefs.K_IMM_RIGHT_ON, false), hasSub = true, subAction = { showRightBtnItems(ctx) }) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_RIGHT_ON, nv)
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "底栏", Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_BOTTOM_BAR, nv)
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "昵称/文案", Prefs.bool(Prefs.K_IMM_NICKNAME, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_NICKNAME, nv)
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "合集", Prefs.bool(Prefs.K_IMM_COLLECTION, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_COLLECTION, nv)
        })
        container.addView(divider(ctx))
        container.addView(itemSwitchRow(ctx, "", "金币红包", Prefs.bool(Prefs.K_IMM_GOLD, false)) { nv ->
            Prefs.setBoolSync(ctx, Prefs.K_IMM_GOLD, nv)
        })
        showSheetScroll(ctx, "自定义隐藏", { showImmersive(ctx) }, container)
    }

    private val TOPBAR_ITEMS = arrayOf("左上角按钮", "王者送福利", "游戏", "玩游戏", "短剧", "同城", "关注", "发现", "精选", "直播", "搜索")
    private val RIGHT_ITEMS = arrayOf("关注", "喜欢", "评论", "收藏", "转发", "音乐封面")

    private fun showTopBarItems(ctx: Context) {
        Prefs.reload()
        val selected = Prefs.strSet(Prefs.K_IMM_TOPBAR).toMutableSet()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (s in TOPBAR_ITEMS) {
            container.addView(itemSwitchRow(ctx, "", s, s in selected) { nv ->
                if (nv) selected.add(s) else selected.remove(s)
                Prefs.setStrSetSync(ctx, Prefs.K_IMM_TOPBAR, selected)
            })
            container.addView(divider(ctx))
        }
        showSheetScroll(ctx, "顶栏隐藏项", { showHideCustom(ctx) }, container)
    }

    private fun showRightBtnItems(ctx: Context) {
        Prefs.reload()
        val selected = Prefs.strSet(Prefs.K_IMM_RIGHT_ITEMS).toMutableSet()
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (s in RIGHT_ITEMS) {
            container.addView(itemSwitchRow(ctx, "", s, s in selected) { nv ->
                if (nv) selected.add(s) else selected.remove(s)
                Prefs.setStrSetSync(ctx, Prefs.K_IMM_RIGHT_ITEMS, selected)
            })
            container.addView(divider(ctx))
        }
        showSheetScroll(ctx, "右侧按钮隐藏项", { showHideCustom(ctx) }, container)
    }

    private fun showFilter(ctx: Context) {
        Prefs.reload()
        val rows = arrayOf(
            arrayOf("过滤广告内容", Prefs.K_FLT_ADS, true, false),
            arrayOf("过滤广告视频", Prefs.K_FLT_ADVIDEO, true, false),
            arrayOf("过滤图文内容", Prefs.K_FLT_IMAGE, false, false),
            arrayOf("过滤直播内容", Prefs.K_FLT_LIVE, false, false),
            arrayOf("过滤AI生成内容", Prefs.K_FLT_AI, false, false),
            arrayOf("过滤电商内容", Prefs.K_FLT_EC, false, false),
            arrayOf("过滤影视内容", Prefs.K_FLT_DRAMA, true, false),
            arrayOf("按点赞数过滤", Prefs.K_FLT_LIKE_ON, true, true),
            arrayOf("按字段过滤", Prefs.K_FLT_KW_ON, false, true),
            arrayOf("优化无更多视频", Prefs.K_FLT_NOMORE, true, false),
        )
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
        var thInput: EditText? = null
        var kwInput: EditText? = null
        for (r in rows) {
            @Suppress("UNCHECKED_CAST")
            val key = r[1] as String
            val def = r[2] as Boolean
            container.addView(itemSwitchRow(ctx, "", r[0] as String, Prefs.bool(key, def)) { nv ->
                Prefs.setBoolSync(ctx, key, nv)
            })
            if (r[3] == true) {
                val isLike = key == Prefs.K_FLT_LIKE_ON
                val label = TextView(ctx).apply {
                    text = if (isLike) "点赞数阈值（低于此值过滤，0=不限制）" else "按字段过滤关键词（逗号分隔）"; textSize = 13f
                    setPadding(dp(ctx, 28), dp(ctx, 6), dp(ctx, 28), dp(ctx, 2))
                }
                outline(label)
                container.addView(label)
                val et = EditText(ctx).apply {
                    if (isLike) {
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setText(Prefs.int(Prefs.K_FLT_LIKE_TH, 1000).toString())
                    } else {
                        setText(Prefs.str(Prefs.K_FLT_KEYWORDS, ""))
                    }
                    textSize = 15f
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                    background = roundRect(ctx, 8f, cInputBg(ctx))
                    setHintTextColor(-1426063361)
                }
                outline(et)
                container.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                et.setOnFocusChangeListener { _, hf ->
                    if (!hf) {
                        if (isLike) Prefs.setIntSync(ctx, Prefs.K_FLT_LIKE_TH, et.text.toString().toIntOrNull() ?: 0)
                        else Prefs.setStrSync(ctx, Prefs.K_FLT_KEYWORDS, et.text.toString().trim())
                    }
                }
                if (isLike) thInput = et else kwInput = et
                container.addView(space(ctx, 8))
            }
            container.addView(divider(ctx))
        }
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val dm = ctx.resources.displayMetrics
        val maxH = (dm.heightPixels * 3) / 4
        showSheetCustom(ctx, "内容过滤", scroll, "", {}, { show(ctx) })
    }

    private fun showPerf(ctx: Context) {
        Prefs.reload()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
        val rows = arrayOf(
            Triple("仅主进程注入", Prefs.K_PERF_MAINPROC, "跳过消息/推送/沙盒等子进程，加速启动、防无响应（重启快手生效）"),
            Triple("过滤判定缓存", Prefs.K_PERF_FCACHE, "同一视频只判定一次，减少滑动卡顿"),
            Triple("低频配置同步", Prefs.K_PERF_LOWFREQ, "配置广播从30秒降至5分钟一次，消除后台风暴"),
            Triple("模块日志静默", Prefs.K_PERF_QUIET, "停止刷屏级诊断日志，减少主线程开销"),
            Triple("刷屏日志拦截", Prefs.K_PERF_LOGSPAM, "native层拦截P2P日志刷屏+Invalid resource ID错误（降GC压力90%）"),
            Triple("拦截摇一摇广告", Prefs.K_PERF_SENSOR, "阻断加速度计高频监听，减少耗电发热（可能影响重力类功能）"),
        )
        for ((title, key, desc) in rows) {
            val def = key != Prefs.K_PERF_SENSOR
            container.addView(purifySwitchRow(ctx, title, desc, Prefs.bool(key, def)) { nv ->
                Prefs.setBoolSync(ctx, key, nv)
            })
            container.addView(divider(ctx))
        }
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        showSheetCustom(ctx, "性能优化", scroll, "", {}, { show(ctx) })
    }

    private fun showPurify(ctx: Context) {
        Prefs.reload()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
        val rows = arrayOf(
            Triple("拦截推送服务", Prefs.K_PURIFY_PUSH, "阻止 MatrixPushV3Service 启动，减少后台推送唤醒"),
            Triple("拦截日志上报", Prefs.K_PURIFY_LOG, "阻断 ConanLogContentProvider 数据上报，减少隐私采集"),
            Triple("拦截 WebView 沙盒", Prefs.K_PURIFY_WEBVIEW, "阻止 SandboxedProcessService0 启动，减少 WebView 子进程开销"),
        )
        for ((title, key, desc) in rows) {
            container.addView(purifySwitchRow(ctx, title, desc, Prefs.bool(key, true)) { nv ->
                Prefs.setBoolSync(ctx, key, nv)
            })
            container.addView(divider(ctx))
        }
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        showSheetCustom(ctx, "快手净化", scroll, "", {}, { show(ctx) })
    }

    private fun purifySwitchRow(ctx: Context, title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 12), dp(ctx, 24), dp(ctx, 12))
            background = ripple(ctx)
        }
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(ctx).apply {
            text = title; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }.also { outline(it) })
        textCol.addView(TextView(ctx).apply {
            text = desc; textSize = 12f; setPadding(0, dp(ctx, 3), 0, 0)
            setTextColor(0xB3FFFFFF.toInt())
        }.also { outline(it) })
        row.addView(textCol)
        val (sw, toggle) = createIosSwitch(ctx, checked) { nv -> onToggle(nv) }
        sw.isClickable = true; sw.isFocusable = true
        sw.setOnClickListener { toggle() }
        row.addView(sw, LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 32)))
        row.setOnClickListener { toggle() }
        return row
    }


    private fun unwrapActivity(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c != null) {
            if (c is Activity) return c
            c = if (c is ContextWrapper) c.baseContext else break
        }
        return null
    }

    private var currentDialog: android.app.Dialog? = null

    private fun attachOverlay(ctx: Context, glass: GlassPanel) {
        val activity = unwrapActivity(ctx) ?: run { Logger.d("attach: no activity"); return }
        detachOverlay()
        // crossBlur 跨窗实时模糊（既定方案）：window 缩到面板大小 + FLAG_BLUR_BEHIND +
        // blurBehindRadius（SF 实时模糊 SurfaceView 视频，零采样天然同步）。
        // 需手机端 KernelSU root shell 执行 settings put global pms_settings_blur_enabled 1
        // 启用 crossBlur（One UI 默认禁用）；未启用时 fallback dimAmount 暗化。
        val dlg = android.app.Dialog(activity)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setCanceledOnTouchOutside(true)
        dlg.setCancelable(true)
        dlg.setOnCancelListener { detachOverlay() }
        dlg.setContentView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val w = dlg.window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val dm = ctx.resources.displayMetrics
        w.setWindowAnimations(0)
        val winW = dm.widthPixels - dp(ctx, 80)
        w.setLayout(winW, ViewGroup.LayoutParams.WRAP_CONTENT)
        w.setGravity(Gravity.CENTER)
        w.setDimAmount(0f)
        // 返回键 = 关闭菜单
        dlg.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) {
                detachOverlay(); true
            } else false
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                val crossOk = activity.windowManager.isCrossWindowBlurEnabled
                if (crossOk) {
                    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    val attrs = w.attributes
                    attrs.blurBehindRadius = dp(activity, 12)
                    w.attributes = attrs
                } else {
                    w.setDimAmount(0.5f)
                }
                Logger.always("attach: sfBlur=12dp crossBlurOk=$crossOk sdk=${android.os.Build.VERSION.SDK_INT} winW=${winW}")
            } catch (t: Throwable) { Logger.d("attach blur fail: ${t.message}") }
        } else {
            w.setDimAmount(0.5f)
        }
        currentOverlay = glass
        currentDialog = dlg
        try {
            dlg.show()
        } catch (t: Throwable) {
            Logger.d("attach show fail: ${t.message}")
            currentDialog = null
        }
    }

    private fun detachOverlay() {
        currentDialog?.let { d ->
            try { d.dismiss() } catch (_: Throwable) {}
        }
        currentDialog = null
        currentOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        currentOverlay = null
    }

    private fun buildGlass(ctx: Context): GlassPanel {
        // crossBlur 跨窗实时模糊配方：SF blur(12dp) 实时透视频（attachOverlay 设置）
        // + 本类自绘表面层（wash/gloss/rim/投影照抄 LiquidGlassPanel/HostLayout）
        return GlassPanel(ctx, dp(ctx, 36).toFloat())
    }

    private fun showSheet(ctx: Context, title: String, items: List<Item>, onBack: (() -> Unit)?, switchRow: View? = null) {
        val glass = buildGlass(ctx)
        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(header(ctx, title, onBack != null, { onBack?.invoke() }, { detachOverlay() }))
        panel.addView(divider(ctx))
        if (switchRow != null) {
            panel.addView(switchRow)
            panel.addView(divider(ctx))
        }
        for ((index, item) in items.withIndex()) {
            panel.addView(itemRow(ctx, item))
            if (index < items.size - 1) panel.addView(divider(ctx))
        }
        panel.addView(space(ctx, 6))
        val dm = ctx.resources.displayMetrics
        val maxH = (dm.heightPixels * 3) / 4
        val wSpec = View.MeasureSpec.makeMeasureSpec(dm.widthPixels - dp(ctx, 88), View.MeasureSpec.AT_MOST)
        panel.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        if (panel.measuredHeight > maxH) {
            val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
            scroll.addView(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            glass.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH))
        } else {
            glass.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        attachOverlay(ctx, glass)
        jellyEnter(panel)
    }

    private fun showSheetScroll(ctx: Context, title: String, onBack: (() -> Unit)?, body: View) {
        val glass = buildGlass(ctx)
        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(header(ctx, title, onBack != null, { onBack?.invoke() }, { detachOverlay() }))
        panel.addView(divider(ctx))
        panel.addView(body)
        panel.addView(space(ctx, 6))
        val dm = ctx.resources.displayMetrics
        val maxH = (dm.heightPixels * 3) / 4
        val wSpec = View.MeasureSpec.makeMeasureSpec(dm.widthPixels - dp(ctx, 88), View.MeasureSpec.AT_MOST)
        panel.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        if (panel.measuredHeight > maxH) {
            val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
            scroll.addView(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            glass.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH))
        } else {
            glass.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        attachOverlay(ctx, glass)
        jellyEnter(panel)
    }

    private fun showSheetCustom(ctx: Context, title: String, body: View, positiveBtn: String, onPositive: () -> Unit, onBack: () -> Unit) {
        val glass = buildGlass(ctx)
        val panel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(header(ctx, title, true, { onBack() }, { detachOverlay() }))
        panel.addView(divider(ctx))
        val bodyWrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4)) }
        bodyWrap.addView(body)
        panel.addView(bodyWrap)
        if (positiveBtn.isNotEmpty()) {
            panel.addView(divider(ctx))
            panel.addView(buttonRow(ctx, positiveBtn, onPositive))
        }
        panel.addView(space(ctx, 6))
        val dm = ctx.resources.displayMetrics
        val maxH = (dm.heightPixels * 3) / 4
        val wSpec = View.MeasureSpec.makeMeasureSpec(dm.widthPixels - dp(ctx, 88), View.MeasureSpec.AT_MOST)
        panel.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        if (panel.measuredHeight > maxH) {
            val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
            scroll.addView(panel, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            glass.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH))
        } else {
            glass.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        attachOverlay(ctx, glass)
        jellyEnter(panel)
    }

    private fun header(ctx: Context, title: String, hasBack: Boolean, onBack: () -> Unit, onClose: () -> Unit): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 12), dp(ctx, 14))
        }
        if (hasBack) {
            val back = TextView(ctx).apply {
                text = "‹"; textSize = 26f; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF),
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x59000000) },
                    null
                )
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40))
            }
            outline(back)
            back.setOnClickListener {
                val before = currentOverlay
                onBack()
                if (currentOverlay === before) detachOverlay()
            }
            bar.addView(back)
            bar.addView(space(ctx, 0, 10))
        }
        if (title == "ManJiao") {
            val stroke = TextView(ctx).apply {
                text = title; textSize = 28f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.12f
                setTextColor(Color.WHITE)
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 7f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val fill = TextView(ctx).apply {
                text = title; textSize = 28f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                letterSpacing = 0.12f
                setTextColor(0xFFA0C4FF.toInt())
                post {
                    try {
                        val w = paint.measureText(text.toString())
                        paint.shader = LinearGradient(0f, 0f, w, 0f, 0xFFA0C4FF.toInt(), 0xFFC9A0FF.toInt(), Shader.TileMode.CLAMP)
                        invalidate()
                    } catch (_: Throwable) {}
                }
            }
            val wrap = FrameLayout(ctx)
            wrap.addView(stroke, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            wrap.addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            bar.addView(wrap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            bar.addView(TextView(ctx).apply {
                text = title; setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 20f
                setTextColor(Color.WHITE)
                setShadowLayer(11f, 0f, 0f, Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        val close = TextView(ctx).apply {
            text = "✕"; textSize = 22f; gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x59000000) },
                null
            )
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40))
        }
        outline(close)
        close.setOnClickListener { onClose() }
        bar.addView(close)
        return bar
    }

    private fun itemRow(ctx: Context, item: Item): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 10), dp(ctx, 24), dp(ctx, 10))
            isClickable = true; isFocusable = true
            background = ripple(ctx)
        }
        if (item.icon.isNotEmpty()) {
            row.addView(TextView(ctx).apply {
                text = item.icon; textSize = 20f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
            })
            row.addView(space(ctx, 0, 14))
        }
        row.addView(TextView(ctx).apply {
            text = item.title; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { outline(it) })
        if (item.hasSub) {
            row.addView(TextView(ctx).apply {
                text = "›"; textSize = 22f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24))
            }.also { outline(it) })
        }
        row.setOnClickListener {
            val before = currentOverlay
            item.action()
            if (currentOverlay === before) detachOverlay()
        }
        return row
    }

    private fun itemSwitchRow(ctx: Context, icon: String, title: String, checked: Boolean, hasSub: Boolean = false, subAction: (() -> Unit)? = null, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 10), dp(ctx, 24), dp(ctx, 10))
            background = ripple(ctx)
        }
        if (icon.isNotEmpty()) {
            row.addView(TextView(ctx).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
            })
            row.addView(space(ctx, 0, 14))
        }
        row.addView(TextView(ctx).apply {
            text = title; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { outline(it) })
        val (sw, toggle) = createIosSwitch(ctx, checked) { nv -> onToggle(nv) }
        sw.isClickable = true; sw.isFocusable = true
        sw.setOnClickListener { toggle() }
        row.addView(sw, LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 32)))
        val arrowW = dp(ctx, 40)
        row.addView(space(ctx, 0, 10))
        if (hasSub) {
            row.addView(TextView(ctx).apply {
                text = "›"; textSize = 22f; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(arrowW, dp(ctx, 44))
            }.also { outline(it) }.also { tv -> tv.setOnClickListener {
                val before = currentOverlay
                subAction?.invoke()
                if (currentOverlay === before) detachOverlay()
            } })
            row.setOnClickListener {
                val before = currentOverlay
                subAction?.invoke()
                if (currentOverlay === before) detachOverlay()
            }
        } else {
            row.addView(space(ctx, 0, arrowW))
            row.setOnClickListener { toggle() }
        }
        return row
    }

    private fun createIosSwitch(ctx: Context, checked: Boolean, onToggle: (Boolean) -> Unit): Pair<View, () -> Unit> {
        val trackW = dp(ctx, 56)
        val trackH = dp(ctx, 32)
        val knobSize = dp(ctx, 26)
        val pad = (trackH - knobSize) / 2
        val colorOn = 0xCC4CAF50.toInt()
        val colorOff = 0xCC757575.toInt()

        val container = FrameLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(trackW, trackH) }
        val trackBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = trackH / 2f
            setColor(if (checked) colorOn else colorOff)
        }
        val track = View(ctx).apply { background = trackBg; elevation = dp(ctx, 1).toFloat() }
        container.addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val knob = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dp(ctx, 4).toFloat()
        }
        val knobLp = FrameLayout.LayoutParams(knobSize, knobSize, Gravity.CENTER_VERTICAL)
        knobLp.leftMargin = if (checked) trackW - knobSize - pad else pad
        container.addView(knob, knobLp)

        var isOn = checked
        val toggle: () -> Unit = {
            isOn = !isOn
            trackBg.setColor(if (isOn) colorOn else colorOff)
            track.background = trackBg
            val fromLeft = knobLp.leftMargin
            val targetLeft = if (isOn) trackW - knobSize - pad else pad
            val dx = (targetLeft - fromLeft).toFloat()
            knob.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction {
                knob.animate().translationX(dx).scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(OvershootInterpolator(2.4f)).withEndAction {
                        knobLp.leftMargin = targetLeft
                        knob.translationX = 0f
                        knob.layoutParams = knobLp
                    }.start()
            }.start()
            onToggle(isOn)
        }
        return Pair(container, toggle)
    }

    private fun buttonRow(ctx: Context, label: String, onAction: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 16))
        }
        val btn = TextView(ctx).apply {
            text = label; textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundRect(ctx, 12f, C_PRIMARY)
            setPadding(dp(ctx, 40), dp(ctx, 14), dp(ctx, 40), dp(ctx, 14))
            isClickable = true; isFocusable = true
        }
        outline(btn)
        btn.setOnClickListener {
            val before = currentOverlay
            onAction()
            if (currentOverlay === before) detachOverlay()
        }
        row.addView(btn)
        return row
    }

    private fun roundRect(ctx: Context, radius: Float, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius * ctx.resources.displayMetrics.density
        }
    }

    private fun ripple(ctx: Context): RippleDrawable {
        val content = ColorDrawable(Color.TRANSPARENT)
        val mask = roundRect(ctx, 0f, cDiv(ctx))
        return RippleDrawable(android.content.res.ColorStateList.valueOf(cRipple(ctx)), content, mask)
    }

    private fun divider(ctx: Context): View {
        return View(ctx).apply {
            setBackgroundColor(cDiv(ctx))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(dp(ctx, 20), 0, dp(ctx, 20), 0)
            }
        }
    }

    private fun space(ctx: Context, h: Int, w: Int = ViewGroup.LayoutParams.MATCH_PARENT): View {
        return View(ctx).apply { layoutParams = LinearLayout.LayoutParams(w, dp(ctx, h)) }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun toast(ctx: Context, msg: String) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
}
