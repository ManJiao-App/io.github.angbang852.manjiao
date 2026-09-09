package com.manjiao.assistant.ui

import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.InputType
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

import com.manjiao.assistant.KsClass
import com.manjiao.assistant.R
import com.manjiao.assistant.data.Prefs

class SettingsActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var bgImg: ImageView
    private lateinit var glass: GlassPanel
    private lateinit var panel: LinearLayout

    override fun onPause() {
        super.onPause()
        // 离开时全量推送（对齐快手进程可能错过的配置；快手收不到也无害）
        try { Prefs.broadcastAll(this) } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        Prefs.reload()
        // 拉取快手侧当前配置（用户可能在快手悬浮菜单里改过，防止这里的旧值显示/回滚）
        try {
            sendBroadcast(android.content.Intent(Prefs.ACTION_PULL).addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
        } catch (_: Throwable) {}
        showMain()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        Prefs.init(this)
        Prefs.reload()
        buildRoot()
        showMain()
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun buildRoot() {
        root = FrameLayout(this)
        // 背景=清晰背景图（全屏，不模糊）
        bgImg = ImageView(this).apply {
            setImageResource(R.drawable.bg_main)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(bgImg)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        container.addView(titleView("ManJiao"))
        container.addView(space(2))
        // app 侧背景无 SF 跨窗模糊加持：wash 在模块默认 40% 基础上调 50%
        //（60% 偏实被要求降）；模块菜单不传该参数保持原值
        val washBoost = if (isDark()) 0x802F3036.toInt() else 0x80EFF1F7.toInt()
        glass = GlassPanel(this, dp(36).toFloat(), washBoost)
        // ★ 菜单背景模糊层：仅菜单面板区域内叠一份 RenderEffect 模糊的背景图副本
        //（全屏背景图保持清晰）。放 GlassPanel 之下、与其内框精确对齐（margin=shadowPad），
        // 绘制栈 = 模糊背景 → wash/gloss/rim 玻璃表面 → 菜单内容
        val glassWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val blurBg = ImageView(this).apply {
            setImageResource(R.drawable.bg_main)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    val r = dp(10).toFloat()
                    setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
                } catch (_: Throwable) {}
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(36).toFloat())
                }
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(glass.shadowPad, glass.shadowPad, glass.shadowPad, glass.shadowPad)
            }
        }
        glassWrap.addView(blurBg)
        glass.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        glassWrap.addView(glass)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        glass.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(glassWrap)
        root.addView(container)
        setContentView(root)
    }

    private fun clearPanel() { panel.removeAllViews() }

    private fun showMain() {
        clearPanel()
        val items = listOf(
            Item("📺", "沉浸式页面") { showImmersive() },
            Item("✋", "手势功能") { showGesture() },
            Item("🎬", "播放控制") { showPlayback() },
            Item("🚫", "内容过滤") { showFilter() },

            Item("⚡", "性能优化") { showPerf() },
            Item("🧹", "快手净化") { showPurify() },
            Item("ℹ️", "关于") { showAbout() },
        )
        for ((i, item) in items.withIndex()) {
            panel.addView(itemRow(item))
            if (i < items.size - 1) panel.addView(divider())
        }
        jellyEnter(panel)
    }

    private fun showGesture() {
        clearPanel()
        panel.addView(header("手势功能", true))
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "禁止双击点赞", Prefs.bool(Prefs.K_GS_NO_DBL_LIKE, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_GS_NO_DBL_LIKE, nv)
            toast(if (nv) "已禁止双击点赞" else "已恢复双击点赞")
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "双击打开评论区", Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_GS_OPEN_COMMENT, nv)
            if (nv && Prefs.bool(Prefs.K_GS_OPEN_MENU, false)) {
                Prefs.setBoolSync(this, Prefs.K_GS_OPEN_MENU, false)
                toast("已自动关闭「双击打开模块菜单」")
                showGesture()
            }
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "双击打开模块菜单", Prefs.bool(Prefs.K_GS_OPEN_MENU, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_GS_OPEN_MENU, nv)
            if (nv && Prefs.bool(Prefs.K_GS_OPEN_COMMENT, false)) {
                Prefs.setBoolSync(this, Prefs.K_GS_OPEN_COMMENT, false)
                toast("已自动关闭「双击打开评论区」")
                showGesture()
            }
        })
        jellyEnter(panel)
    }

    private fun showPlayback() {
        clearPanel()
        panel.addView(header("播放控制", true))
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "停止循环播放", Prefs.bool(Prefs.K_PB_NO_LOOP, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_PB_NO_LOOP, nv)
            toast(if (nv) "已停止循环播放" else "已恢复循环播放")
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("", "后台暂停播放", Prefs.bool(Prefs.K_PB_BG_PAUSE, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_PB_BG_PAUSE, nv)
            toast(if (nv) "已开启后台暂停" else "已关闭后台暂停")
        })
        jellyEnter(panel)
    }

    private fun showImmersive() {
        clearPanel()
        panel.addView(header("沉浸式页面", true))
        panel.addView(divider())
        panel.addView(itemSwitchRow("🚀", "一键沉浸", Prefs.bool(Prefs.K_IMM_ON, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_ON, nv)
            toast(if (nv) "一键沉浸已开启" else "一键沉浸已关闭")
        })
        panel.addView(divider())
        panel.addView(itemRow(Item("🫥", "自定义隐藏", true) { showHideCustom() }))
        jellyEnter(panel)
    }

    private fun showHideCustom() {
        clearPanel()
        panel.addView(header("自定义隐藏", true) { showImmersive() })
        panel.addView(divider())
        panel.addView(itemSwitchRow("📊", "顶栏", Prefs.bool(Prefs.K_IMM_TOPBAR_ON, false), hasSub = true, subAction = { showTopBarItems() }) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_TOPBAR_ON, nv)
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("👉", "右侧按钮", Prefs.bool(Prefs.K_IMM_RIGHT_ON, false), hasSub = true, subAction = { showRightBtnItems() }) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_RIGHT_ON, nv)
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("📋", "底栏", Prefs.bool(Prefs.K_IMM_BOTTOM_BAR, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_BOTTOM_BAR, nv)
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("✍️", "昵称/文案", Prefs.bool(Prefs.K_IMM_NICKNAME, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_NICKNAME, nv)
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("📂", "合集", Prefs.bool(Prefs.K_IMM_COLLECTION, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_COLLECTION, nv)
        })
        panel.addView(divider())
        panel.addView(itemSwitchRow("🪙", "金币红包", Prefs.bool(Prefs.K_IMM_GOLD, false)) { nv ->
            Prefs.setBoolSync(this, Prefs.K_IMM_GOLD, nv)
        })
        jellyEnter(panel)
    }

    private val TOPBAR_ITEMS = arrayOf("左上角按钮", "王者送福利", "游戏", "玩游戏", "短剧", "同城", "关注", "发现", "精选", "直播", "搜索")
    private val RIGHT_ITEMS = arrayOf("关注", "喜欢", "评论", "收藏", "转发", "音乐封面")

    private fun showTopBarItems() {
        clearPanel()
        panel.addView(header("顶栏隐藏项", true) { showHideCustom() })
        panel.addView(divider())
        val selected = Prefs.strSet(Prefs.K_IMM_TOPBAR).toMutableSet()
        for (s in TOPBAR_ITEMS) {
            panel.addView(itemSwitchRow("", s, s in selected) { nv ->
                if (nv) selected.add(s) else selected.remove(s)
                Prefs.setStrSetSync(this, Prefs.K_IMM_TOPBAR, selected)
            })
            panel.addView(divider())
        }
        jellyEnter(panel)
    }

    private fun showRightBtnItems() {
        clearPanel()
        panel.addView(header("右侧按钮隐藏项", true) { showHideCustom() })
        panel.addView(divider())
        val selected = Prefs.strSet(Prefs.K_IMM_RIGHT_ITEMS).toMutableSet()
        for (s in RIGHT_ITEMS) {
            panel.addView(itemSwitchRow("", s, s in selected) { nv ->
                if (nv) selected.add(s) else selected.remove(s)
                Prefs.setStrSetSync(this, Prefs.K_IMM_RIGHT_ITEMS, selected)
            })
            panel.addView(divider())
        }
        jellyEnter(panel)
    }

    private fun showFilter() {
        clearPanel()
        panel.addView(header("内容过滤", true))
        panel.addView(divider())
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
        for (r in rows) {
            @Suppress("UNCHECKED_CAST")
            val key = r[1] as String
            val def = r[2] as Boolean
            panel.addView(itemSwitchRow("", r[0] as String, Prefs.bool(key, def)) { nv ->
                Prefs.setBoolSync(this, key, nv)
            })
            if (r[3] == true) {
                val isLike = key == Prefs.K_FLT_LIKE_ON
                panel.addView(inputRow(
                    if (isLike) "点赞数阈值（低于此值过滤，0=不限制）" else "按字段过滤关键词（逗号分隔）",
                    if (isLike) Prefs.int(Prefs.K_FLT_LIKE_TH, 1000).toString() else Prefs.str(Prefs.K_FLT_KEYWORDS, ""),
                    if (isLike) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                ) { v ->
                    if (isLike) Prefs.setIntSync(this, Prefs.K_FLT_LIKE_TH, v.toIntOrNull() ?: 0)
                    else Prefs.setStrSync(this, Prefs.K_FLT_KEYWORDS, v.trim())
                })
                panel.addView(space(8))
            }
            panel.addView(divider())
        }
        jellyEnter(panel)
    }

    private fun showPerf() {
        clearPanel()
        panel.addView(header("性能优化", true))
        panel.addView(divider())
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
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply {
                text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }.also { outline(it) })
            val (sw, toggle) = createIosSwitch(Prefs.bool(key, def)) { nv -> Prefs.setBoolSync(this, key, nv) }
            sw.isClickable = true; sw.isFocusable = true
            sw.setOnClickListener { toggle() }
            line.addView(sw, LinearLayout.LayoutParams(dp(56), dp(32)))
            row.addView(line)
            row.addView(TextView(this).apply {
                text = desc; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(dp(2), dp(3), 0, 0)
            }.also { outline(it) })
            row.setOnClickListener { toggle() }
            panel.addView(row)
            panel.addView(divider())
        }
        jellyEnter(panel)
    }

    private fun showPurify() {
        clearPanel()
        panel.addView(header("快手净化", true))
        panel.addView(divider())
        val rows = arrayOf(
            Triple("拦截推送服务", Prefs.K_PURIFY_PUSH, "阻止 MatrixPushV3Service 启动，减少后台推送唤醒"),
            Triple("拦截日志上报", Prefs.K_PURIFY_LOG, "阻断 ConanLogContentProvider 数据上报，减少隐私采集"),
            Triple("拦截 WebView 沙盒", Prefs.K_PURIFY_WEBVIEW, "阻止 SandboxedProcessService0 启动，减少 WebView 子进程开销"),
        )
        for ((title, key, desc) in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply {
                text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }.also { outline(it) })
            val (sw, toggle) = createIosSwitch(Prefs.bool(key, true)) { nv -> Prefs.setBoolSync(this, key, nv) }
            sw.isClickable = true; sw.isFocusable = true
            sw.setOnClickListener { toggle() }
            line.addView(sw, LinearLayout.LayoutParams(dp(56), dp(32)))
            row.addView(line)
            row.addView(TextView(this).apply {
                text = desc; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); setPadding(dp(2), dp(3), 0, 0)
            }.also { outline(it) })
            row.setOnClickListener { toggle() }
            panel.addView(row)
            panel.addView(divider())
        }
        jellyEnter(panel)
    }

    private fun inputRow(label: String, value: String, inputType: Int = InputType.TYPE_CLASS_TEXT, onSave: (String) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
        box.addView(TextView(this).apply { text = label; textSize = 14f }.also { outline(it) })
        box.addView(space(6))
        val et = EditText(this).apply {
            setText(value); this.inputType = inputType; textSize = 15f
            setTextColor(Color.WHITE); setHintTextColor(0x88FFFFFF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(8).toFloat(); setColor(0x33FFFFFF.toInt()) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onSave(et.text.toString()) }
        et.setOnEditorActionListener { _, _, _ -> onSave(et.text.toString()); et.clearFocus(); false }
        box.addView(et)
        return box
    }

    private class Item(val icon: String, val title: String, val hasSub: Boolean = true, val action: () -> Unit)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun outline(tv: TextView) { tv.setTextColor(Color.WHITE); tv.setShadowLayer(4f, 0f, 0f, Color.BLACK) }

    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)) }

    private fun divider() = View(this).apply {
        setBackgroundColor(0x33000000.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(20), 0, dp(20), 0) }
    }

    private fun titleView(text: String): View {
        val stroke = TextView(this).apply {
            this.text = text; textSize = 40f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setTextColor(Color.WHITE)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 9f
        }
        val fill = TextView(this).apply {
            this.text = text; textSize = 40f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setTextColor(0xFFA0C4FF.toInt())
            post {
                try {
                    val w = paint.measureText(text.toString())
                    val left = (width - w) / 2f
                    paint.shader = LinearGradient(left, 0f, left + w, 0f, 0xFFA0C4FF.toInt(), 0xFFC9A0FF.toInt(), Shader.TileMode.CLAMP)
                    invalidate()
                } catch (_: Throwable) {}
            }
        }
        return FrameLayout(this).apply {
            addView(stroke, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            addView(fill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
    }


    private fun header(text: String, hasBack: Boolean, onBack: () -> Unit = { showMain() }): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(16), dp(12), dp(14)) }
        if (hasBack) {
            val back = TextView(this).apply {
                this.text = "‹"; textSize = 24f; gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF),
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x59000000) },
                    null
                )
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            }
            outline(back); back.setOnClickListener { onBack() }; bar.addView(back)
            bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 0) })
        }
        bar.addView(TextView(this).apply {
            this.text = text; textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }.also { outline(it) })
        return bar
    }

    private fun itemRow(item: Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16)); isClickable = true; isFocusable = true; background = ripple()
        }
        row.addView(TextView(this).apply { text = item.icon; textSize = 22f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)) })
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(14), 0) })
        row.addView(TextView(this).apply {
            text = item.title; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { outline(it) })
        if (item.hasSub) row.addView(TextView(this).apply { text = "›"; textSize = 24f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)) }.also { outline(it) })
        row.setOnClickListener { item.action() }
        return row
    }

    private fun itemSwitchRow(icon: String, title: String, checked: Boolean, hasSub: Boolean = false, subAction: (() -> Unit)? = null, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16)); isClickable = true; isFocusable = true; background = ripple()
        }
        if (icon.isNotEmpty()) {
            row.addView(TextView(this).apply { text = icon; textSize = 22f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)) })
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(14), 0) })
        }
        row.addView(TextView(this).apply {
            text = title; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also { outline(it) })
        val (sw, toggle) = createIosSwitch(checked) { onToggle(it) }
        sw.isClickable = true; sw.isFocusable = true
        sw.setOnClickListener { toggle() }
        row.addView(sw, LinearLayout.LayoutParams(dp(56), dp(32)))
        val arrowW = dp(40)
        if (hasSub) {
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 0) })
            row.addView(TextView(this).apply {
                text = "›"; textSize = 24f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(arrowW, dp(44))
                isClickable = true; isFocusable = true
            }.also { outline(it) }.also { tv -> tv.setOnClickListener { subAction?.invoke() } })
            row.setOnClickListener { subAction?.invoke() }
        } else {
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 0) })
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(arrowW, 0) })
            row.setOnClickListener { toggle() }
        }
        return row
    }

    private fun createIosSwitch(checked: Boolean, onToggle: (Boolean) -> Unit): Pair<View, () -> Unit> {
        val trackW = dp(56); val trackH = dp(32); val knobSize = dp(26); val pad = (trackH - knobSize) / 2
        val colorOn = 0xCC4CAF50.toInt(); val colorOff = 0xCC757575.toInt()
        val container = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(trackW, trackH) }
        val trackBg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = trackH / 2f; setColor(if (checked) colorOn else colorOff) }
        val track = View(this).apply { background = trackBg; elevation = dp(1).toFloat() }
        container.addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val knob = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }; elevation = dp(4).toFloat() }
        val knobLp = FrameLayout.LayoutParams(knobSize, knobSize, Gravity.CENTER_VERTICAL)
        knobLp.leftMargin = if (checked) trackW - knobSize - pad else pad
        container.addView(knob, knobLp)
        var isOn = checked
        val toggle: () -> Unit = {
            isOn = !isOn; trackBg.setColor(if (isOn) colorOn else colorOff); track.background = trackBg
            val fromLeft = knobLp.leftMargin; val targetLeft = if (isOn) trackW - knobSize - pad else pad; val dx = (targetLeft - fromLeft).toFloat()
            knob.animate().scaleX(0.8f).scaleY(0.8f).setDuration(70).withEndAction {
                knob.animate().translationX(dx).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(OvershootInterpolator(2.4f)).withEndAction {
                    knobLp.leftMargin = targetLeft; knob.translationX = 0f; knob.layoutParams = knobLp
                }.start()
            }.start()
            onToggle(isOn)
        }
        return Pair(container, toggle)
    }

    private fun buttonRow(label: String, onAction: () -> Unit): View {
        val p40 = dp(40); val p14 = dp(14); val p12 = dp(12)
        val btn = TextView(this).apply {
            text = label; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(p40, p14, p40, p14); isClickable = true; isFocusable = true
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(0xFFFF6600.toInt()); cornerRadius = p12.toFloat() }
        }
        outline(btn); btn.setOnClickListener { onAction() }
        return btn
    }

    private fun ripple(): RippleDrawable = RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), ColorDrawable(Color.TRANSPARENT), null)

    private fun jellyEnter(view: View) {
        view.scaleX = 0.92f; view.scaleY = 0.92f; view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun appVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0" } catch (_: Throwable) { "1.0" }

    private fun showAbout() {
        clearPanel()
        panel.addView(header("关于", true))
        panel.addView(divider())
        panel.addView(infoRow("当前版本", appVersion()))
        panel.addView(divider())
        val urlRow = infoRow("项目网址", "github.com/ManJiao-App/ManJiao")
        urlRow.isClickable = true; urlRow.isFocusable = true; urlRow.background = ripple()
        urlRow.setOnClickListener {
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/ManJiao-App/ManJiao"))) } catch (_: Throwable) {}
        }
        panel.addView(urlRow)
        panel.addView(divider())
        panel.addView(buttonRow("检查更新") { checkUpdate(false) })
        panel.addView(space(8))
        jellyEnter(panel)
        checkUpdate(true)
    }

    private fun infoRow(label: String, value: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
        row.addView(TextView(this).apply { text = label; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }.also { outline(it) })
        row.addView(TextView(this).apply { text = value; textSize = 15f; setTextColor(0xCCFFFFFF.toInt()) }.also { outline(it) })
        return row
    }

    private fun checkUpdate(auto: Boolean) {
        if (!auto) toast("检查更新中…")
        Thread {
            try {
                val conn = java.net.URL("https://api.github.com/repos/ManJiao-App/ManJiao/releases/latest").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "ManJiao")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val latest = Regex("\"tag_name\"\\s*:\\s*\"(?:v)?([^\"]+)\"").find(body)?.groupValues?.get(1) ?: return@Thread
                val dlUrl = Regex("\"html_url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "https://github.com/ManJiao-App/ManJiao/releases"
                val cur = appVersion()
                runOnUiThread {
                    if (latest != cur) {
                        toast("发现新版本 $latest")
                        if (!auto) { try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(dlUrl))) } catch (_: Throwable) {} }
                    } else { if (!auto) toast("已是最新版本") }
                }
            } catch (t: Throwable) {
                runOnUiThread { if (!auto) toast("检查更新失败") }
            }
        }.start()
    }
}
