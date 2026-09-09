package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.data.CurrentVideo
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

object ContentFilterHook {
    private val handler = Handler(Looper.getMainLooper())
    private val AD_TEXTS = arrayOf("广告", "赞助", "sponsored", "推广")
    private val AI_TEXTS = arrayOf("AI生成", "AI制作", "ai生成", "疑似含AI", "AI创作")
    private val DRAMA_IDS = arrayOf("tube_panel", "serial", "element_tube_tk_action", "group_bottom_root", "feed_set", "collection")
    private val DRAMA_TEXTS = arrayOf("看全集", "文娱榜", "选集", "上集", "下集", "全剧", "剧集", "正片")
    private val MOVIE_HINT = arrayOf("电影", "电视剧", "影视", "解说", "剪辑", "全集", "第", "集", "剧")
    @Volatile private var tracked: Activity? = null
    private var lastSkipTime = 0L

    @Volatile private var xpRef: XposedInterface? = null
    @Volatile private var clRef: ClassLoader? = null
    @Volatile private var vmRef: Any? = null
    @Volatile private var fragRef: Any? = null
    @Volatile private var adpRef: Any? = null
    private val adpRefs = java.util.Collections.synchronizedList(mutableListOf<Any>())
    private var elemDumped = false
    @Volatile private var qpClassRef: Class<*>? = null
    private var lastJumpTime = 0L
    private val hookedDsClasses = mutableSetOf<String>()

    // ★ 直播页上下文放行：精选 tab 的过滤/删除链路（filterListArgs/laFind/TRUEDEL/zap）
    // 对直播页（LiveSlideActivity 等 com.kuaishou.live.* 页面）是灾难——直播页与精选容器
    // 共享数据引用（Ip() 从 slideplay 容器取 items），删共享列表/zap 直播实体字段会把
    // 直播页 pager 掏空或打成空壳 → 直播间黑屏、滑不动、底栏切换失效（实证 07:51 del=1 left=0 后卡死）。
    // 直播页在前台时：所有内容判定放行（shouldFilterFeed=false）、构造 zap 跳过。
    // 非快手主包 Activity（系统弹窗等）不改变状态，防弹窗期间误恢复过滤。
    @Volatile private var liveTop = false
    private fun hookActivityLifecycle(xp: XposedInterface) {
        try {
            val actCls = Class.forName("android.app.Activity", false, null)
            val mOn = actCls.getDeclaredMethod("onResume")
            xp.hook(mOn).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("liveTop.onResume").intercept { chain ->
                val r = chain.proceed()
                try {
                    val act = chain.thisObject as? Activity
                    if (act != null) {
                        val cn = act.javaClass.name
                        if (cn.startsWith("com.kuaishou.live.")) {
                            if (!liveTop) Logger.always("LIVETOP on: $cn")
                            liveTop = true
                        } else if (cn.startsWith("com.smile.gifmaker") || cn.startsWith("com.yxcorp.") || cn.startsWith("com.kwai.")) {
                            if (liveTop) Logger.always("LIVETOP off: $cn")
                            liveTop = false
                        }
                    }
                } catch (_: Throwable) {}
                r
            }
            Logger.d("hookActivityLifecycle done")
        } catch (t: Throwable) { Logger.d("hookActivityLifecycle fail: ${t.message}") }
    }

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        xpRef = xp
        clRef = cl
        hookActivityLifecycle(xp)
        Logger.d("VER=rerank-v2 hook() cl=$cl")
        val targets = setOf(
            KsClass.PHOTO_DETAIL_ACTIVITY, KsClass.PHOTO_DETAIL_ACTIVITY_TABLET,
            "com.yxcorp.gifshow.HomeActivity", "com.yxcorp.gifshow.HomeActivityTablet"
        )
        for (a in targets) {
            val c = Reflect.findClass(a, cl) ?: continue
            val mOn = Reflect.findMethod(c, "onResume", 0)
            if (mOn != null) xp.hook(mOn).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("filter.on.$a").intercept { chain ->
                chain.proceed()
                try { startTrack(chain.thisObject as Activity) } catch (_: Throwable) {}
                null
            }
            val mOff = Reflect.findMethod(c, "onPause", 0)
            if (mOff != null) xp.hook(mOff).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("filter.off.$a").intercept { chain ->
                chain.proceed()
                try { stopTrack(chain.thisObject as Activity) } catch (_: Throwable) {}
                null
            }
            Logger.d("FilterHook on $a")
        }
        hookNasaFragment(xp, cl)
        hookFeedResponse(xp, cl)
        hookCacheClasses(xp, cl)
        hookMilanoContainers(xp, cl)
        hookPageLists(xp, cl)
        hookLiveRerank(xp, cl)
        hookKnhbT0(xp, cl)
        hookLiveFeedConstruct(xp, cl)
        hookKrnProbe(xp, cl)
        hookKrnReactContainerView(xp, cl)
        hookFragmentCrashGuard(xp, cl)
    }

    private fun hookFragmentCrashGuard(xp: XposedInterface, cl: ClassLoader) {
        try {
            val cn = "com.kwai.component.photo.detail.slide.groot.DetailSlidePlayFragment"
            val cls = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: return
            var hooked = 0
            for (m in cls.declaredMethods) {
                if (m.name != "onCreate" && m.name != "onCreateView") continue
                try {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("crashGuard.${m.name}").intercept { chain ->
                        try {
                            chain.proceed()
                        } catch (e: NullPointerException) {
                            Logger.always("crashGuard ${m.name} NPE: " + e.message)
                            null
                        }
                    }
                    hooked++
                } catch (_: Throwable) {}
            }
            if (hooked > 0) Logger.d("hookFragmentCrashGuard hooked " + hooked + " methods")
        } catch (t: Throwable) { Logger.d("hookFragmentCrashGuard fail: " + t.message) }
    }

    // ★ 直播带货预览卡（RN CombinedCard）取证：hook KRN 容器 KrnFragment 生命周期，
    // 打印参数(bundleId等) + 创建调用栈，反查 feed 里谁在创建它，找到 Java 层数据源头。
    private var krnProbeHooked = false
    private var krnProbeRetries = 0
    private fun hookKrnProbe(xp: XposedInterface, cl: ClassLoader) {
        if (krnProbeHooked) return
        krnProbeHooked = true
        val clRef = cl
        val retry = object : Runnable {
            override fun run() {
                var done = false
                for (cn in listOf("com.kuaishou.krn.page.KrnFragment", "KrnFragment")) {
                    val c = Reflect.findClass(cn, clRef) ?: continue
                    var hooked = 0
                    for (mn in listOf("onViewCreated", "onCreateView", "onAttach", "setArguments", "onResume")) {
                        val m = c.declaredMethods.firstOrNull { it.name == mn } ?: continue
                        try {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnProbe.$cn.$mn").intercept { chain ->
                                try {
                                    if (krnProbeCount < 6) {
                                        krnProbeCount++
                                        val args = chain.args.joinToString(",") { a ->
                                            when (a) {
                                                null -> "null"
                                                is android.os.Bundle -> {
                                                    val sb = StringBuilder()
                                                    try {
                                                        for (key in a.keySet()) {
                                                            val v = a.get(key)
                                                            sb.append("$key=${if (v is String) v.take(80) else v?.javaClass?.simpleName ?: "null"}; ")
                                                        }
                                                    } catch (_: Throwable) {}
                                                    "Bundle[$sb]"
                                                }
                                                else -> "${a.javaClass.simpleName}"
                                            }
                                        }
                                        Logger.always("KRNPROBE $mn #$krnProbeCount args=[$args]\n" +
                                            Thread.currentThread().stackTrace.drop(1).take(22).joinToString("\n"))
                                    }
                                } catch (_: Throwable) {}
                                chain.proceed()
                            }
                            hooked++
                        } catch (t: Throwable) {
                            Logger.always("krnProbe hook $mn exc: ${t.message}")
                        }
                    }
                    Logger.always("krnProbe hooked $cn methods=$hooked")
                    if (hooked > 0) { done = true; break }
                }
                if (!done && krnProbeRetries < 40) {
                    krnProbeRetries++
                    if (krnProbeRetries == 1 || krnProbeRetries % 10 == 0) Logger.always("krnProbe retry #$krnProbeRetries: KrnFragment not loaded yet")
                    handler.postDelayed(this, 2000)
                } else if (!done) {
                    Logger.always("krnProbe GIVE UP after $krnProbeRetries retries: KrnFragment never loaded")
                }
            }
        }
        handler.postDelayed(retry, 2000)
    }
    private var krnProbeCount = 0

    // ★ KRN 电商/直播带货卡渲染源头拦截（模拟器已验证判定锚点）：
    // KrnReactContainerView.getLaunchModel 返回的 LaunchModel.f Bundle 含
    // bundleId=Kwaishop*（实证 KwaishopRNCPrecisionMarketing/KwaishopCLivePreviewCommodityCard
    // 同族）。命中直播带货 bundle 时清空该 Bundle 键——卡片拿不到数据即不渲染
    // （删除式拦截，非替换）。KRNLM 日志保留限次审计。
    private var krnRcvHooked = false
    private var krnRcvRetries = 0
    private var krnLmDiag = 0

    private fun hookKrnReactContainerView(xp: XposedInterface, cl: ClassLoader) {
        if (krnRcvHooked) return
        krnRcvHooked = true
        val clRef = cl
        val retry = object : Runnable {
            override fun run() {
                val c = Reflect.findClass("com.kuaishou.krn.page.KrnReactContainerView", clRef)
                if (c == null) {
                    if (krnRcvRetries < 40) {
                        krnRcvRetries++
                        if (krnRcvRetries == 1 || krnRcvRetries % 10 == 0) Logger.always("krnRcv retry #$krnRcvRetries")
                        handler.postDelayed(this, 2000)
                    } else Logger.always("krnRcv GIVE UP")
                    return
                }
                var hooked = 0
                val gm = c.declaredMethods.firstOrNull { it.name == "getLaunchModel" }
                if (gm != null) {
                    try {
                        xp.hook(gm).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnRcv.getLaunchModel").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                if (r != null) {
                                    var fBundle: android.os.Bundle? = null
                                    var fc: Class<*>? = r.javaClass
                                    var lvl = 0
                                    while (fc != null && fc != Any::class.java && lvl < 3 && fBundle == null) {
                                        for (f in fc!!.declaredFields) {
                                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                            if (f.type == android.os.Bundle::class.java) {
                                                try { f.isAccessible = true; fBundle = f.get(r) as? android.os.Bundle } catch (_: Throwable) {}
                                                if (fBundle != null) break
                                            }
                                        }
                                        fc = fc.superclass; lvl++
                                    }
                                    val bid = fBundle?.get("bundleId") as? String
                                    val isShop = bid != null && bid.startsWith("Kwaishop")
                                    if (isShop && Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                                        val keys = fBundle!!.keySet().toList()
                                        for (k in keys) fBundle!!.remove(k)
                                        Logger.always("KRNZAP #$krnLmDiag bundleId=$bid keysCleared=${keys.size}")
                                        krnLmDiag++
                                    } else if (krnLmDiag < 3 && bid != null) {
                                        Logger.always("KRNLM pass bundleId=$bid")
                                        krnLmDiag++
                                    }
                                }
                            } catch (_: Throwable) {}
                            r
                        }
                        hooked++
                    } catch (t: Throwable) { Logger.always("krnRcv gm hook exc: ${t.message}") }
                }
                // ★ 第二条渲染路径兜底：KrnReactRootView.setBundleId 是所有 KRN root
                // view 注入 bundle 的统一入口（CombinedCard 平铺路径不走 getLaunchModel）。
                // 命中 Kwaishop* 且直播开关开 → 清 ReactStyleProps 阻断渲染（删除式）。
                val rv = Reflect.findClass("com.kuaishou.krn.widget.react.KrnReactRootView", clRef)
                if (rv != null) {
                    val sb = rv.declaredMethods.firstOrNull { it.name == "setBundleId" }
                    val gp = rv.declaredMethods.firstOrNull { it.name == "getReactStyleProps" }
                    if (sb != null) {
                        try {
                            xp.hook(sb).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnRv.setBundleId").intercept { chain ->
                                val bid = chain.args.firstOrNull() as? String
                                if (bid != null && bid.startsWith("Kwaishop") && Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                                    Logger.always("KRNZAP-ROOT bundleId=$bid")
                                    try {
                                        // attach 时 getBundleId 仍为 null（实证 bid=null），
                                        // setBundleId 是 bundleId 首次可读时机，此处置 GONE
                                        // 保留到上屏（显示层删除式拦截）
                                        (chain.thisObject as? android.view.View)?.visibility = android.view.View.GONE
                                        Logger.always("KRNZAP-GONE bundleId=$bid")
                                    } catch (_: Throwable) {}
                                    try {
                                        val root = chain.thisObject
                                        if (gp != null) {
                                            val style = gp.invoke(root)
                                            if (style != null) {
                                                var sf: Class<*>? = style.javaClass
                                                while (sf != null && sf != Any::class.java) {
                                                    for (f in sf!!.declaredFields) {
                                                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                                        try {
                                                            f.isAccessible = true
                                                            if (!f.type.isPrimitive) f.set(style, null)
                                                        } catch (_: Throwable) {}
                                                    }
                                                    sf = sf.superclass
                                                }
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }
                                chain.proceed()
                            }
                        } catch (t: Throwable) { Logger.always("krnRv sb hook exc: ${t.message}") }
                    }
                }
                // ★ 显示层拦截（终防线）：attach 时 bundleId 已注入（setBundleId
                // 先于 attach 实证），双保险。命中 Kwaishop* → GONE。
                val rv2 = Reflect.findClass("com.kuaishou.krn.widget.react.KrnReactRootView", clRef)
                if (rv2 != null) {
                    val att = rv2.declaredMethods.firstOrNull { it.name == "onAttachedToWindow" }
                    val gb = rv2.declaredMethods.firstOrNull { it.name == "getBundleId" }
                    if (att != null && gb != null) {
                        try {
                            xp.hook(att).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("krnRv.attach").intercept { chain ->
                                val proceedResult = chain.proceed()
                                try {
                                    val root = chain.thisObject
                                    val bid = gb.invoke(root) as? String
                                    if (bid != null && bid.startsWith("Kwaishop") && Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                                        (root as? android.view.View)?.visibility = android.view.View.GONE
                                        Logger.always("KRNZAP-GONE-ATT bundleId=$bid")
                                    }
                                } catch (_: Throwable) {}
                                proceedResult
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }
    // 直播重排模块：com.kuaishou.live.rerank 在 VerticalViewPager 滚动时把
    // LiveStreamFeed 直接塞进首页信息流。它的类被混淆（e$b.onPageScrolled 回调 +
    // d.t / e$d.E 内部方法），但数据一定以 List / 单项实体的形式跨方法。
    // 策略：按「回调签名」hook onPageScrolled，并扫描 rerank 包的 List 返回方法过滤。
    private val hookedRerankCls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private fun hookLiveRerank(xp: XposedInterface, cl: ClassLoader) {
        val pkg = "com.kuaishou.live.rerank"
        val tryNames = listOf("e", "e\$b", "d", "e\$d", "c", "b")
        var hookedAny = false
        for (tn in tryNames) {
            val cn = "$pkg.$tn"
            if (!hookedRerankCls.add(cn)) continue
            val cc = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: continue
            hookedAny = true
            Logger.d("rerank cls: $cn methodCount=${cc.declaredMethods.size}")
            for (m in cc.declaredMethods) {
                val nm = m.name
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") || it.name.contains("Collection") }
                // 跳过无 List 参数且返回值是 primitive/void/String 的方法（没数据可过滤）
                if (!hasListParam && (m.returnType.isPrimitive || m.returnType == Void.TYPE || m.returnType == java.lang.String::class.java)) continue
                if (nm.startsWith("getCurrent") || nm == "getPhoto" || nm == "getItem") continue
                Logger.safe("rerank.${cn}.${nm}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.${cn}.${nm}").intercept { chain ->
                        // 源头拦截：在参数 List 里就把 LiveStreamFeed 剔掉，直播根本进不了信息流
                        if (Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                            try { filterListArgs(chain.args) } catch (_: Throwable) {}

                        }
                        val r = chain.proceed()
                        try {
                            if (r is List<*>) {
                                val before = r.size
                                filterResult(r)
                                if (r.size != before && rerankListDiag < 20) {
                                    rerankListDiag++
                                    Logger.d("rerank list ${nm} filtered: $before -> ${r.size}")
                                }
                            } else if (r != null) {
                                val q = findQpInObject(r)
                                if (q != null && shouldFilterFeed(q)) {
                                    if (rerankSingleDiag < 20) {
                                        rerankSingleDiag++
                                        Logger.d("rerank single ${nm}: ${readCaption(q)?.take(20)}")
                                    }
                                    return@intercept null
                                }
                            }
                            } catch (_: Throwable) {}

                            r
                    }
                }
            }
        }
        // onPageScrolled (int,int,float) 回调 + onPageSelected(int)：滚动到位后立即清一次 adapter 直播
        for (tn in listOf("e\$b")) {
            val cn = "$pkg.$tn"
            val cc = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: continue
            for (m in cc.declaredMethods) {
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    Logger.safe("rerank.sel.${cn}.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.sel.${cn}.${m.name}").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                val pos = chain.args.getOrNull(0) as? Int ?: -1
                                if (rerankScrollDiag < 10) {
                                    rerankScrollDiag++
                                    Logger.d("rerank selected #$pos")
                                }
                                // ★ 翻页时机清洗：rerank 把直播/广告插进 pager adapter 数据集
                                // （不在 VM 真源），每次翻页触发 LAFIND 全图扫描（含 ADP 根）
                                if (!Logger.quiet) try { laFind() } catch (_: Throwable) {}
                            } catch (_: Throwable) {}
                            r
                        }
                    }
                } else if (m.parameterTypes.size == 3 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    Logger.safe("rerank.scroll.${cn}.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.scroll.${cn}.${m.name}").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                if (rerankScrollDiag < 10) {
                                    rerankScrollDiag++
                                    val pos = chain.args.getOrNull(0) as? Int ?: -1
                                    Logger.d("rerank scroll #$pos")
                                }
                                if (!Logger.quiet) try { laFind() } catch (_: Throwable) {}
                            } catch (_: Throwable) {}
                            r
                        }
                    }
                }
            }
        }
                // ★ d.j(int)：rerank 预判「滑动方向前方第2项是未曝光直播」（上屏前最早预知点，
        // 反编译实证 e$b.onPageScrolled → j(iN) → true 则提前 u() 发起替换请求）。
        // ret==true 时强制立即 laFind（绕 2.5s 节流）清真源——用户滑到直播卡前至少还有
        // 2 个位置（1~2 秒），后台毫秒级删除必赶得上 → 直播卡在滑到前已从数据集消失
        try {
            val jd = Class.forName("com.kuaishou.live.rerank.d", false, cl)
            for (m in jd.declaredMethods) {
                if (m.name == "j" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType && m.returnType == java.lang.Boolean.TYPE) {
                    Logger.safe("rerank.d.j") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rerank.d.j").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                if (r == true && !liveTop) {
                                    val iN = chain.args.getOrNull(0) as? Int ?: -1
                                    if (rerankJDiag < 20) { rerankJDiag++; Logger.always("RERANKJ hit iN=" + iN + " (live 2 ahead) -> force laFind") }

                                    try {

                                        val dumpAdp = adpRef ?: adpRefs.firstOrNull()

                                        if (dumpAdp != null) {

                                            var dc: Class<*>? = dumpAdp.javaClass

                                            var dlvl = 0

                                            while (dc != null && dc != Any::class.java && dlvl < 3) {

                                                for (df in dc!!.declaredFields) {

                                                    if (java.lang.reflect.Modifier.isStatic(df.modifiers)) continue

                                                    try {

                                                        df.isAccessible = true

                                                        val dv = df.get(dumpAdp) ?: continue

                                                        if (dv is List<*>) {

                                                            for ((didx, del) in dv.withIndex()) {

                                                                if (didx > 6) break

                                                                val dqp = del ?: continue

                                                                val dent = Reflect.readAny(dqp, "mEntity") ?: dqp

                                                                val dpm = Reflect.readAny(dent, "mPhotoMeta")

                                                                val dlm = Reflect.readAny(dent, "mLivePlaybackMeta")

                                                                val dcm = Reflect.readAny(dent, "mCommonMeta")

                                                                val dcap = (dcm?.let { Reflect.readString(it, "mCaption") } ?: "").take(30)

                                                                val dun = readUserName(dqp, dent).take(20)

                                                                Logger.always("RJDUMP adp[" + didx + "] ent=" + dent.javaClass.simpleName + " lm=" + (dlm != null) + " liveSid=" + (dlm?.let { Reflect.readAny(it, "mLiveStreamId") != null }) + " state=" + (dpm?.let { Reflect.readBool(it, "mCurrentLivingState") }) + " useLive=" + (dpm?.let { Reflect.readBool(it, "mUseLive") }) + " merch=" + (dpm?.let { Reflect.readAny(it, "mMerchantLiveInfo") != null }) + " btn=" + (dpm?.let { Reflect.readAny(it, "mInteractionLiveCardButton") != null }) + " clip=" + (dpm?.let { Reflect.readAny(it, "mLiveStreamClipInfo") != null }) + " cap=" + dcap + " user=" + dun)

                                                            }

                                                            break

                                                        }

                                                    } catch (_: Throwable) {}

                                                }

                                                dc = dc.superclass; dlvl++

                                            }

                                        }

                                    } catch (_: Throwable) {}

                                    try { laFind(true) } catch (_: Throwable) {}
                                }
                            } catch (_: Throwable) {}
                            r
                        }
                    }
                    Logger.d("hooked rerank.d.j(int)")
                }
            }
        } catch (_: Throwable) {}
if (hookedAny) Logger.d("hookLiveRerank done pkg=$pkg")
    }
    private var rerankJDiag = 0
    private var rerankScrollDiag = 0
    private var rerankListDiag = 0
    private var rerankSingleDiag = 0
    private var plistSkipDiag = 0


    private var lastAdpSelfFix = 0L
    private val adpSelfFixVisited = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
    private fun fixAdapterSelfAlways(adp: Any?) {
        if (adp == null) return
        val now = System.currentTimeMillis()
        if (now - lastAdpSelfFix < 300) return
        lastAdpSelfFix = now
        if (adpSelfFixVisited.size > 800) adpSelfFixVisited.clear()
        if (!adpSelfFixVisited.add(adp)) return
        try {
            var c2: Class<*>? = adp.javaClass
            var lvl2 = 0
            while (c2 != null && c2 != Any::class.java && lvl2 < 4) {
                for (f in c2!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(adp)
                        if (v is MutableList<*> && v.size > 0) {

                            val hits = v.filter { it != null && findQpInObject(it)?.let { q -> shouldFilterFeed(q) } == true }
                            if (hits.isNotEmpty() && v.size - hits.size >= 1) {
                                val cap0 = hits.firstOrNull()?.let { findQpInObject(it)?.let { q -> readCaption(q) } }
                                Logger.d("adpSelfFix ${f.name} fixed=${hits.size} cap0=${cap0?.take(14)}")
                                var fixed = 0
                                for (i in 0 until v.size) {
                                    val el = v[i] ?: continue
                                    val eq = findQpInObject(el)
                                    if (eq != null && shouldFilterFeed(eq)) {
                                        val cleanQp = findCleanQp()
                                        if (cleanQp != null) {
                                            val sw = try { writeQpInto(el, cleanQp) } catch (_: Throwable) { 0 }
                                            val sw2 = if (sw == 0 && qpClassRef?.isAssignableFrom(el.javaClass) == true) {
                                                try { @Suppress("UNCHECKED_CAST") (v as MutableList<Any?>)[i] = cleanQp; 1 } catch (_: Throwable) { 0 }
                                            } else sw
                                            fixed += if (sw2 > 0) 1 else 0
                                        }
                                    }
                                }
                                if (fixed > 0) {
                                    Logger.d("adpSelfFixAl ${f.name} fixed=$fixed sz=${v.size}")
                                    try { Reflect.callMethod(adp, "notifyDataSetChanged") } catch (_: Throwable) {}
                                }
                            }
                            // 幸存干净项入池补充队列
                            for (el in v) {
                                el?.let { e -> findQpInObject(e)?.let { q -> if (!shouldFilterFeed(q)) { try { offerClean(q) } catch (_: Throwable) {} } } }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c2 = c2.superclass; lvl2++
            }
        } catch (_: Throwable) {}
    }
    private fun isDescendantOf(v: View, root: View): Boolean {
        var x: View? = v
        while (x != null) { if (x === root) return true; x = x.parent as? View }
        return false
    }

    // Milano 数据�?= �?PageList（双向分页列表）。hook 其取数方法，
    // 在源头把直播/AI/广告/剧集 项替换成干净项或过滤掉�?
    private val hookedPageLists = mutableSetOf<String>()
    private fun hookPageLists(xp: XposedInterface, cl: ClassLoader) {
        val names = arrayOf(
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.network.CommonFeedSlideBidirectionalPageList",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.network.PostCommonFeedSlidePageList",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.PostLocalFeedSlidePageList",
            "com.yxcorp.gifshow.detail.slideplay.airecommendslide.slide.network.AiRecommendSlidePageList"
        )
        for (cn in names) {
            val cc = Reflect.findClass(cn, cl) ?: continue
            val already = synchronized(hookedPageLists) { !hookedPageLists.add(cn) }
            if (already) continue
            Logger.d("hookPageList: $cn")
            var c: Class<*>? = cc
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (m in c!!.declaredMethods) {
                    val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    val isIntP = m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
                    val nonPrimRet = !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.returnType != java.lang.String::class.java
                    if ((isIntP && nonPrimRet) || isRetList) {
                        Logger.d("  plist method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                        Logger.safe("hookPageList.${cn}.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("plist.${cn}.${m.name}").intercept { chain ->
                                try { filterListArgs(chain.args) } catch (_: Throwable) {}
                                val r = chain.proceed()
                                try { filterResult(r) } catch (_: Throwable) {}
                                // 源头拦截：单项返回是直播/广告直接返回 null，让 adapter 跳过该位置（不进信息流）
                                try {
                                    if (r != null) {
                                        val q = findQpInObject(r)
                                        if (q != null && shouldFilterFeed(q)) {
                                            if (plistSkipDiag < 30) { plistSkipDiag++; Logger.d("plist skip ${m.name} (${readCaption(q)?.take(15)})") }
                                            return@intercept null
                                        }
                                    }
                                } catch (_: Throwable) {}
                                r
                            }
                        }
                    }
                }
                c = c.superclass; lvl++
            }
        }
    }

    // ★ 方向C：hook knh.b.T0 —— Milano adapter 数据插入入口
    // T0(int startPos, List<QPhoto> before, List<QPhoto> update, List<QPhoto> affected, UpdateType, String reason)
    // 在 List 参数里删脏项 = 直播卡数据不进 adapter = 不上屏
    private var knhbT0Hooked = false
    private var knhbT0Diag = 0
    private var knhbCallDiag = 0
    // ★ 冷启动强制刷新：首批拦到脏项（脏卡可能已先上屏，拦截慢于渲染）→ 2s 后
    // 对 knh.b 数据源触发一次下拉刷新语义的 reload（重新请求第一页 replaceAll，
    // 新批次仍会走本 hook 过滤）＝把屏上的脏卡刷掉。每次进程冷启动只刷一次。
    @Volatile private var bootFlushDone = false
    @Volatile private var bootFlushPending = false
    private var knhbInst: java.lang.ref.WeakReference<Any>? = null
    private fun scheduleBootFlush(src: Any?) {
        if (bootFlushDone || bootFlushPending) return
        bootFlushPending = true
        if (src != null) knhbInst = java.lang.ref.WeakReference(src)
        Logger.d("BOOTFLUSH scheduled 2s")
        handler.postDelayed({
            bootFlushPending = false
            try { doBootFlush() } catch (e: Throwable) { Logger.d("BOOTFLUSH err: ${e.message}") }
        }, 2000)
    }
    private fun doBootFlush() {
        if (bootFlushDone) return
        bootFlushDone = true
        val inst = knhbInst?.get()
        Logger.d("BOOTFLUSH run inst=${inst?.javaClass?.name ?: "null"}")
        // 路径1：数据源实例（含父类）上名字含 refresh/reload/requery 的无参 void 方法
        if (inst != null) {
            var c: Class<*>? = inst.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 6) {
                for (m in c!!.declaredMethods) {
                    if (m.parameterTypes.isNotEmpty() || m.returnType != Void.TYPE) continue
                    val mn = m.name.lowercase()
                    if (!(mn.contains("refresh") || mn.contains("reload") || mn.contains("requery"))) continue
                    try {
                        m.isAccessible = true
                        m.invoke(inst)
                        Logger.d("BOOTFLUSH called ${m.name} on ${c!!.name}")
                        return
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            // 路径2：遍历字段值按运行时类型找 kik.o0 请求器（字段声明类型是 kik.i 接口，
            // 非dnh.包名，须按字段值的实际继承链找 refresh() 无参方法）
            var fc: Class<*>? = inst.javaClass
            var flvl = 0
            while (fc != null && fc != Any::class.java && flvl < 6) {
                for (f in fc!!.declaredFields) {
                    try {
                        f.isAccessible = true
                        val req = f.get(inst) ?: continue
                        if (req is Collection<*> || req is android.view.View) continue
                        var oc: Class<*>? = req.javaClass
                        var ol = 0
                        while (oc != null && oc != Any::class.java && ol < 8) {
                            for (rm in oc!!.declaredMethods) {
                                if (rm.name != "refresh" || rm.parameterTypes.isNotEmpty() || rm.returnType != Void.TYPE) continue
                                try {
                                    rm.isAccessible = true
                                    rm.invoke(req)
                                    Logger.d("BOOTFLUSH called refresh on ${req.javaClass.name} (field ${f.name})")
                                    return
                                } catch (_: Throwable) {}
                            }
                            oc = oc.superclass; ol++
                        }
                    } catch (_: Throwable) {}
                }
                fc = fc.superclass; flvl++
            }
        }
        // 路径3：兜底现有 vmRef 刷新链
        val ok = try { triggerRefresh() } catch (_: Throwable) { false }
        Logger.d("BOOTFLUSH fallback triggerRefresh=$ok")
    }

    // ★ 防重复视频：refresh（BOOTFLUSH/prefetch）重拉第一页可能带回已供给过的视频。
    // 已供给 photoId 历史（LRU 上限 500）+ QPhoto→photoId 身份缓存（每对象只反射一次）。
    // 去重只作用于实测的数据载荷路径：T0 的 args[2]（update 批次）+ E1 的 args[0]，
    // 删批次里 photoId 已在历史中的项；护栏同 filterListArgs（删后至少留 1 或原本 ≤1）。
    private val seenPhotoIds = LinkedHashSet<String>()
    private val photoIdCache = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Any, String?>())
    private var dedupeDiag = 0
    private var dedupeProbe = 0
    private var dedupeStarve = 0
    private fun readPhotoId(qp: Any?): String? {
        if (qp == null) return null
        if (photoIdCache.containsKey(qp)) return photoIdCache[qp]
        val id = try {
            val m = qp.javaClass.getMethod("getPhotoId")
            m.isAccessible = true
            m.invoke(qp) as? String
        } catch (_: Throwable) { null }
        if (photoIdCache.size > 4000) photoIdCache.clear()
        photoIdCache[qp] = id
        return id
    }
    private fun dedupeInsertBatch(batch: MutableList<Any?>?, tag: String): Int {
        if (batch == null || batch.isEmpty()) return 0
        if (dedupeProbe < 3) {
            dedupeProbe++
            val f = batch.firstOrNull()
            Logger.d("knhb dedupe probe $tag size=${batch.size} cls=${f?.javaClass?.name} id=${readPhotoId(f)} hist=${synchronized(seenPhotoIds) { seenPhotoIds.size }}")
        }
        var dup = 0
        val inBatch = HashSet<String>()
        val victims = ArrayList<Any?>()
        for (el in batch) {
            val id = readPhotoId(el) ?: continue
            if (id.isBlank()) continue
            val seenBefore = synchronized(seenPhotoIds) { seenPhotoIds.contains(id) }
            // 历史已供给 或 本批次内重复 → 删
            if (seenBefore || !inBatch.add(id)) victims.add(el)
        }
        // 护栏：删后至少留 1（原本 >1 时），防 replaceAll 收到空列表
        if (victims.isNotEmpty() && batch.size - victims.size < 1) victims.removeAt(victims.size - 1)
        if (victims.isEmpty()) {
            dedupeStarve = 0
            // 幸存项记入历史
            synchronized(seenPhotoIds) {
                for (el in batch) {
                    val id = readPhotoId(el) ?: continue
                    if (id.isNotBlank()) {
                        seenPhotoIds.remove(id); seenPhotoIds.add(id)
                    }
                }
                if (seenPhotoIds.size > 500) {
                    val it = seenPhotoIds.iterator()
                    var drop = seenPhotoIds.size - 500
                    while (drop-- > 0 && it.hasNext()) { it.next(); it.remove() }
                }
            }
            return 0
        }
        for (v in victims) { try { batch.remove(v); dup++ } catch (_: Throwable) {} }
        // ★ 饥饿自愈（2026-09-08 用户报「长时间无更多滑不出」）：长时间刷后 hist 攒满、服务端推荐池
        // 轮回返回看过的视频 → dedupe 全删（left<=1）→ 列表只剩 1 项轮转 → prefetch/loadMore 无限循环
        // 但供给不涨。对策：连续 4 批 dedupe 删后 left<=1（供给无效）→ 清空 hist 重开一轮
        // （接受一轮重复换供给恢复，不卡死）；left>=2 正常批次计数清零。
        if (batch.size <= 1) {
            dedupeStarve++
            if (dedupeStarve >= 4) {
                synchronized(seenPhotoIds) { seenPhotoIds.clear() }
                Logger.always("dedupe starved 4 batches (hist reset) -> supply recover")
                dedupeStarve = 0
            }
        } else dedupeStarve = 0
        if (dedupeDiag < 30) {
            dedupeDiag++
            Logger.d("knhb dedupe $tag dup=$dup left=${batch.size} starve=$dedupeStarve hist=${synchronized(seenPhotoIds) { seenPhotoIds.size }}")
        }
        // 幸存项记入历史
        synchronized(seenPhotoIds) {
            for (el in batch) {
                val id = readPhotoId(el) ?: continue
                if (id.isNotBlank()) {
                    seenPhotoIds.remove(id); seenPhotoIds.add(id)
                }
            }
            if (seenPhotoIds.size > 500) {
                val it = seenPhotoIds.iterator()
                var drop = seenPhotoIds.size - 500
                while (drop-- > 0 && it.hasNext()) { it.next(); it.remove() }
            }
        }
        return dup
    }
    private fun hookKnhbT0(xp: XposedInterface, cl: ClassLoader) {
        if (knhbT0Hooked) return
        val tryNames = listOf("knh.b", "knh\$b")
        for (cn in tryNames) {
            val cc = try { Class.forName(cn, false, cl) } catch (_: Throwable) { null } ?: continue
            knhbT0Hooked = true
            Logger.d("knhb found cls=$cn methodCount=${cc.declaredMethods.size}")
            for (m in cc.declaredMethods) {
                val nm = m.name
                val ptypes = m.parameterTypes
                val hasListParam = ptypes.any { it == java.util.List::class.java }
                if (!hasListParam) continue
                Logger.d("knhb method: $nm(${ptypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                Logger.safe("knhb.$cn.$nm") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("knhb.$cn.$nm").intercept { chain ->
                        try {
                            if (knhbCallDiag < 40) {
                                knhbCallDiag++
                                val sizes = chain.args.map { if (it is List<*>) "L${it.size}" else it?.javaClass?.simpleName ?: "-" }.joinToString(",")
                                Logger.d("knhb call $nm($sizes) hist=${synchronized(seenPhotoIds) { seenPhotoIds.size }}")
                            }
                            val removed = filterListArgs(chain.args)
                            if (removed > 0) {
                                if (knhbT0Diag < 30) {
                                    knhbT0Diag++
                                    Logger.d("knhb.$nm filtered del=$removed")
                                }
                                scheduleBootFlush(chain.thisObject)
                            }
                            // ★ 防重复（单点去重）：E1 全时去重（服务端原始批次主战场）；
                            // T0 仅 refresh 重拉路径（reason 含 firstRequest）去重——loadMore 续拉时
                            // T0 的 update 批次是 E1 刚供给的幸存项（已在 hist），再判重=双重去重误删
                            if (nm == "E1" && ptypes.size == 1) {
                                dedupeInsertBatch(chain.args.getOrNull(0) as? MutableList<Any?>, "E1")
                            } else if (nm == "T0" && ptypes.size == 6) {
                                val reason = chain.args.getOrNull(5) as? String ?: ""
                                if (reason.contains("firstRequest")) {
                                    dedupeInsertBatch(chain.args.getOrNull(2) as? MutableList<Any?>, "T0fr")
                                }
                            }
                        } catch (_: Throwable) {}
                        val r = chain.proceed()
                        try { if (r is List<*>) filterResult(r) } catch (_: Throwable) {}
                        r
                    }
                }
            }
            if (knhbT0Hooked) break
        }
        if (!knhbT0Hooked) Logger.d("knhb NOT FOUND (obfuscated?)")
    }

    // Milano �?feed 架构：直�?hotphoto)/AI(airecommendslide)/视频(commonfeedslide) 各是独立容器�?
    // 容器类被混淆�?a，这里探测其取数方法�?hook 过滤�?
    private val hookedMilanoContainers = mutableSetOf<String>()
    private fun hookMilanoContainers(xp: XposedInterface, cl: ClassLoader) {
        val names = arrayOf(
            "com.yxcorp.gifshow.detail.slideplay.hotphoto.container.a",
            "com.yxcorp.gifshow.detail.slideplay.airecommendslide.a",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.a"
        )
        for (cn in names) {
            val cc = Reflect.findClass(cn, cl) ?: continue
            hookMilanoContainer(xp, cc, cn)
        }
    }

    private fun hookMilanoContainer(xp: XposedInterface, cc: Class<*>, cn: String) {
        synchronized(hookedMilanoContainers) { if (!hookedMilanoContainers.add(cn)) return }
        Logger.d("hookMilano: $cn")
        var c: Class<*>? = cc
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 4) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val ft = f.type.name
                if (ft.contains("List") || ft.contains("Collection") || ft.contains("QPhoto") || ft.contains("Feed")) {
                    Logger.d("  milano field: ${f.name} type=${ft}")
                }
            }
            for (m in c.declaredMethods) {
                val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                if (isRetList || hasListParam) {
                    Logger.d("  milano method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                    Logger.safe("hookMilano.${cn}.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("milano.${cn}.${m.name}").intercept { chain ->
                            try { filterListArgs(chain.args) } catch (_: Throwable) {}
                            val result = chain.proceed()
                            try { filterResult(result) } catch (_: Throwable) {}
                            try { filterResponseFields(chain.thisObject) } catch (_: Throwable) {}
                            result
                        }
                    }
                }
            }
            c = c.superclass; lvl++
        }
    }

    private fun hookCacheClasses(xp: XposedInterface, cl: ClassLoader) {
        val cacheNames = arrayOf(
            "com.yxcorp.gifshow.feed.cache.home.HomeResponseEvictingQueueCorrector",
            "com.yxcorp.gifshow.feed.cache.home.HomeResponseLiveFilter",
            "com.yxcorp.gifshow.feed.cache.home.HomeResponseCache"
        )
        for (cn in cacheNames) {
            val cc = Reflect.findClass(cn, cl) ?: continue
            Logger.d("hookCache: $cn")
            var c: Class<*>? = cc
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (m in c!!.declaredMethods) {
                    val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                    if (isRetList || hasListParam) {
                        Logger.d("  cache method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                        Logger.safe("hookCache.${cn}.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("cache.${cn}.${m.name}").intercept { chain ->
                                try { filterListArgs(chain.args) } catch (_: Throwable) {}
                                val result = chain.proceed()
                                try { filterResult(result) } catch (_: Throwable) {}
                                result
                            }
                        }
                    }
                }
                c = c.superclass; lvl++
            }
        }
    }

    // 源头之王：hook LiveStreamFeed 构造函数，构造时把所有直播标识字段置空/置 false，
    // 让快手判别为非直播，根本不启动直播渲染管线。这样直播卡变成空壳，信息流自动跳过。
    private var liveCtorDiag = 0
    private val liveCtorHookedCls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private fun ensureLiveFeedConstructHooked(ent: Any) {
        val xp = xpRef ?: return
        val c = ent.javaClass
        if (!liveCtorHookedCls.add(c.name)) return
        Logger.d("hookLiveCtor late: ${c.name} ctors=${c.declaredConstructors.size}")
        for (ctor in c.declaredConstructors) {
            Logger.safe("hookLiveCtorLate.${ctor.parameterTypes.size}") {
                xp.hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("liveCtorLate.${c.name}.${ctor.parameterTypes.size}").intercept { chain ->
                    val r = chain.proceed()
                    try {
                        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !liveTop) {
                            if (liveCtorDiag < 1) {
                                liveCtorDiag++
                                var dfc: Class<*>? = r.javaClass
                                while (dfc != null && dfc != Any::class.java) {
                                    val dfcNow = dfc!!
                                    for (f in dfcNow.declaredFields) {
                                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                        try { f.isAccessible = true; Logger.d("  LSF fld: ${dfcNow.simpleName}.${f.name}:${f.type.simpleName}=${f.get(r)?.javaClass?.simpleName ?: "null"}") } catch (_: Throwable) {}
                                    }
                                    dfc = dfcNow.superclass
                                }
                            }
                            var zapped = 0
                            var fc: Class<*>? = r.javaClass
                            while (fc != null && fc != Any::class.java) {
                                val fcNow = fc!!
                                for (f in fcNow.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                    val fnl = f.name.lowercase()
                                    if (fnl.contains("live") || fnl.contains("stream") || fnl.contains("living") || fnl.contains("play")) {
                                        try {
                                            f.isAccessible = true
                                            when {
                                                f.type == Boolean::class.javaPrimitiveType -> { if (f.getBoolean(r)) { f.setBoolean(r, false); zapped++ } }
                                                f.type == Int::class.javaPrimitiveType -> { if (f.getInt(r) != 0) { f.setInt(r, 0); zapped++ } }
                                                f.type == Long::class.javaPrimitiveType -> { if (f.getLong(r) != 0L) { f.setLong(r, 0L); zapped++ } }
                                                else -> { val v = f.get(r); if (v != null) { f.set(r, null); zapped++ } }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                                fc = fcNow.superclass
                            }
                            if (zapped > 0 && liveCtorDiag < 40) { liveCtorDiag++; Logger.d("liveCtor zap zapped=$zapped") }
                        }
                    } catch (_: Throwable) {}
                    r
                }
            }
        }
    }
    private fun hookLiveFeedConstruct(xp: XposedInterface, cl: ClassLoader) {
        val cn = "com.kuaishou.android.model.feed.LiveStreamFeed"
        val c = Reflect.findClass(cn, cl) ?: return
        Logger.d("hookLiveFeedConstruct: $cn ctors=${c.declaredConstructors.size}")
        // dump 字段名（仅一次）
        if (liveCtorDiag == 0) {
            var fc: Class<*>? = c
            while (fc != null && fc != Any::class.java) {
                for (f in fc!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    Logger.d("  LiveStreamFeed fld: ${fc.simpleName}.${f.name}:${f.type.simpleName}")
                }
                fc = fc.superclass
            }
        }
        for (ctor in c.declaredConstructors) {
            Logger.safe("hookLiveCtor.${ctor.parameterTypes.size}") {
                xp.hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("liveCtor.${cn}.${ctor.parameterTypes.size}").intercept { chain ->
                    val r = chain.proceed()
                    try {
                        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && !liveTop) {
                            var zapped = 0
                            var fc: Class<*>? = r.javaClass
                            while (fc != null && fc != Any::class.java) {
                                val fcNow = fc!!
                                for (f in fcNow.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                    val fnl = f.name.lowercase()
                                    if (fnl.contains("live") || fnl.contains("stream") || fnl.contains("living") || fnl.contains("play")) {
                                        try {
                                            f.isAccessible = true
                                            when {
                                                f.type == Boolean::class.javaPrimitiveType -> { if (f.getBoolean(r)) { f.setBoolean(r, false); zapped++ } }
                                                f.type == Int::class.javaPrimitiveType -> { if (f.getInt(r) != 0) { f.setInt(r, 0); zapped++ } }
                                                f.type == Long::class.javaPrimitiveType -> { if (f.getLong(r) != 0L) { f.setLong(r, 0L); zapped++ } }
                                                else -> { val v = f.get(r); if (v != null) { f.set(r, null); zapped++ } }
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                                fc = fcNow.superclass
                            }
                            if (zapped > 0 && liveCtorDiag < 30) { liveCtorDiag++; Logger.d("liveCtor zap zapped=$zapped") }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    // ==================== LAWATCH：l.a 写入监控 + 前置删除 ====================
    // 痛点：真源清洗（sanitize deep:l.a）已生效但时机晚——直播先进 l.a → pager
    // 渲染上屏（用户先看到）→ V0 getter 触发 → 快照命中才补删真源（画面卡住）。
    // B(List) hook 实证未命中合并路径 → 快手走私有内部方法写 l.a。
    // l.a 是 CopyOnWriteArrayList，外部写入必经 add/addAll 系方法 → 全局 hook 这些
    // 方法 + 身份比对（this === laRef），即可 100% 覆盖所有写入路径：
    //  1) addAll/addAllAbsent：before 阶段直接过滤参数 Collection（前置删除，
    //     脏项根本进不了 l.a，早于渲染）
    //  2) add/addIfAbsent：after 阶段立即移除（同一调用栈内，仍早于渲染）
    //  3) 每次命中打印完整调用栈（LAWATCH stack）→ 定位私有合并方法
    // 性能：CopyOnWriteArrayList 是热点类，callback 首行 identity 比对开销可忽略；
    // 定位到私有路径后应移除本插桩，改为精准 hook 合并方法

    @Volatile private var laRef: Any? = null
    @Volatile private var laWatchArmed = false
    private val laLogN = java.util.concurrent.atomic.AtomicInteger(0)
    private val retDelQp = java.util.Collections.synchronizedList(ArrayList<Any?>())

    private fun armLaWatch(list: Any) {
        laRef = list
        // ★ LAIDS：VM.i（vmFields 实证 CopyOnWriteArrayList）与 l.a 双列表对照 dump +
        // V0 快照脏元素身份反查（retDelQp 记录 filterResult 删过的 QPhoto，看它藏在哪个字段）
        if (!Logger.quiet) Logger.safe("laTrace") {
            val vm = vmRef
            if (vm != null) {
                for (fname in arrayOf("i", "l")) {
                    try {
                        val holder = if (fname == "i") vm else Reflect.readAny(vm, "l") ?: continue
                        val lst = Reflect.readAny(holder, if (fname == "i") "i" else "a") as? List<*> ?: continue
                        val sb = StringBuilder("LAIDS $fname id=")
                            .append(System.identityHashCode(lst))
                            .append(" size=").append(lst.size).append(" :")
                        lst.take(14).forEachIndexed { idx, e ->
                            sb.append(" [$idx]")
                            if (e == null) { sb.append("null"); return@forEachIndexed }
                            sb.append(e.javaClass.simpleName.ifEmpty { e.javaClass.name.substringAfterLast('.') })
                            val ent = try { Reflect.readAny(e, "mEntity") } catch (_: Throwable) { null }
                            if (ent != null) sb.append("/").append(ent.javaClass.simpleName)
                            try {
                                synchronized(retDelQp) {
                                    if (retDelQp.any { it === e }) sb.append("*DIRTY")
                                }
                            } catch (_: Throwable) {}
                        }
                        Logger.d(sb.toString())
                    } catch (_: Throwable) {}
                }
            }
        }
        if (laWatchArmed) return
        val xp = xpRef ?: return
        synchronized(this) {
            if (laWatchArmed) return
            laWatchArmed = true
            Logger.safe("armLaWatch") {
                val cow = java.util.concurrent.CopyOnWriteArrayList::class.java
                val specs = listOf(
                    Triple("addAll", arrayOf<Class<*>>(java.util.Collection::class.java), true),
                    Triple("addAllAbsent", arrayOf<Class<*>>(java.util.Collection::class.java), true),
                    Triple("add", arrayOf<Class<*>>(Any::class.java), false),
                    Triple("addIfAbsent", arrayOf<Class<*>>(Any::class.java), false)
                )
                var ok = 0
                for ((mn, pt, isBatch) in specs) {
                    try {
                        val m = cow.getDeclaredMethod(mn, *pt)
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("lawatch.$mn").intercept { chain ->
                            if (chain.thisObject !== laRef) return@intercept chain.proceed()
                            val arg = chain.args.firstOrNull()
                            var dirtySingle: Any? = null
                            if (isBatch && arg is MutableCollection<*>) {
                                // 前置删除：脏项从参数集合剔除，addAll 执行时已无脏项
                                @Suppress("UNCHECKED_CAST")
                                val col = arg as MutableCollection<Any?>
                                val dirty = col.filter { laElDirty(it) }
                                if (dirty.isNotEmpty()) {
                                    val before = col.size
                                    try { col.removeAll(dirty.toSet()) } catch (_: Throwable) {}
                                    Logger.always("LADEL $mn removed=${dirty.size}/$before left=${col.size}")
                                    laStack(mn)
                                }
                            } else if (!isBatch && arg != null && laElDirty(arg)) {
                                dirtySingle = arg
                            }
                            val r = chain.proceed()
                            if (dirtySingle != null) {
                                // 单元素：add 已执行，立即从 l.a 移除（同一调用栈内，早于渲染）
                                try { (chain.thisObject as MutableCollection<*>).remove(dirtySingle) } catch (_: Throwable) {}
                                Logger.always("LADEL $mn single removed ${(dirtySingle as Any).javaClass.name}")
                                laStack(mn)
                            }
                            r
                        }
                        ok++
                    } catch (_: Throwable) {}
                }
                Logger.always("LAWATCH armed: ok=$ok/4 (identity filter on l.a)")
            }
        }
    }

    // 精确判定：仅 feed 数据对象（QPhoto 本体 / mEntity 含 Feed 的包装）参与判脏。
    // 教训 2026-09-08：宽泛判定把 FragmentManager.mAdded、l.c Presenter 回调表、
    // mBackPressInterceptors、slideprocess 追踪器全误删（匿名类 q$a/d$c 不含
    // "Presenter" 字样守卫失效）——非 feed 对象一律不碰
    private fun laElDirty(el: Any?): Boolean {
        if (el == null) return false
        if (liveTop) return false
        return try {
            val rawCls = el.javaClass.name
            if (rawCls.contains("Presenter") || rawCls.contains("Callback") ||
                rawCls.contains("Fragment") || rawCls.contains("Interceptor") ||
                rawCls.contains("Executer") || rawCls.contains("Executor")) return false
            val qp = qpClassRef ?: return false
            val ent = try { Reflect.readAny(el, "mEntity") } catch (_: Throwable) { null }
            val isFeedObj = qp.isAssignableFrom(el.javaClass) ||
                (ent != null && ent.javaClass.name.contains("Feed"))
            if (!isFeedObj) return false
            val q = if (qp.isAssignableFrom(el.javaClass)) el else findQpInObject(el) ?: return false
            shouldFilterFeed(q) ||
                (Prefs.bool(Prefs.K_FLT_LIVE, false) && rawCls.contains("LiveStreamFeed")) ||
                (Prefs.bool(Prefs.K_FLT_ADS, false) && rawCls.contains("AdFeed"))
        } catch (_: Throwable) { false }
    }

    private fun laStack(tag: String) {
        val n = laLogN.incrementAndGet()
        if (n > 20) return
        try {
            val st = Throwable().stackTrace
            val sb = StringBuilder("LAWATCH #$n $tag stack:")
            for (i in 0 until minOf(st.size, 25)) {
                val s = st[i]
                sb.append("\n  at ").append(s.className).append(".").append(s.methodName)
                    .append("(").append(s.fileName).append(":").append(s.lineNumber).append(")")
            }
            Logger.d(sb.toString())
        } catch (_: Throwable) {}
    }
    // ==================== LAWATCH end ====================

    private var laFindAt = 0L
    private var truesrcCapDiag = 0
    // ★ 身份引用数组：hook 回调热路径禁用任何集合类（SetFromMap.contains 内部
    // 会触发其他被 hook 的集合方法 → 递归风暴实证），只用纯 === 数组遍历
    private val trueListRefs = java.util.concurrent.CopyOnWriteArrayList<Any>()
    private val trueWatchIds = java.util.concurrent.CopyOnWriteArrayList<Int>()
    private val hookedTrueCls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val trueListLogN = java.util.concurrent.atomic.AtomicInteger(0)

    private fun isTrueList(t: Any?): Boolean {
        if (t == null) return false
        for (r in trueListRefs) if (r === t) return true
        return false
    }

    // ★ TRUEWATCH：真源 h.m.p.a 写方法 watch（身份比对）——前置删除脏项 + 打调用
    // 栈定位快手合并私有方法。对实际运行时类挂 add/addAll/set 系（ArrayList 走
    // add(E)/add(int,E)/addAll(Collection)/addAll(int,Collection)/set(int,E)）
    private fun armTrueWatch(list: Any) {
        if (!trueListRefs.contains(list)) trueListRefs.add(list)
        val cls0 = list.javaClass
        val xp = xpRef ?: return
        synchronized(this) {
            if (!hookedTrueCls.add(cls0.name)) return
            Logger.safe("armTrueWatch") {
                val cls = list.javaClass
                var ok = 0
                val sigs = mutableListOf<Pair<String, Array<Class<*>>>>()
                sigs.add("add" to arrayOf<Class<*>>(Any::class.java))
                sigs.add("add" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Any::class.java))
                sigs.add("addAll" to arrayOf<Class<*>>(java.util.Collection::class.java))
                sigs.add("addAll" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, java.util.Collection::class.java))
                sigs.add("set" to arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Any::class.java))
                for ((mn, pt) in sigs) {
                    try {
                        val m = cls.getDeclaredMethod(mn, *pt)
                        val isBatch = mn == "addAll"
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("truewatch.${mn}.${pt.size}").intercept { chain ->
                            if (!isTrueList(chain.thisObject)) return@intercept chain.proceed()
                            val arg = chain.args.lastOrNull()
                            var dirtySingle: Any? = null
                            if (isBatch && arg is MutableCollection<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val col = arg as MutableCollection<Any?>
                                val dirty = col.filter { laElDirty(it) }
                                if (dirty.isNotEmpty()) {
                                    val before = col.size
                                    try { col.removeAll(dirty.toSet()) } catch (_: Throwable) {}
                                    Logger.always("TRUEDEL $mn removed=${dirty.size}/$before left=${col.size}")
                                    trueStack(mn)
                                }
                            } else if (!isBatch && arg != null && laElDirty(arg)) {
                                dirtySingle = arg
                            }
                            val r = chain.proceed()
                            if (dirtySingle != null) {
                                try { (chain.thisObject as MutableCollection<*>).remove(dirtySingle) } catch (_: Throwable) {}
                                Logger.always("TRUEDEL $mn single removed")
                                trueStack(mn)
                            }
                            r
                        }
                        ok++
                    } catch (_: Throwable) {}
                }
                Logger.always("TRUEWATCH armed on ${cls.name}: ok=$ok/5")
            }
        }
    }

    private fun trueStack(tag: String) {
        val n = trueListLogN.incrementAndGet()
        if (n > 20) return
        try {
            val st = Thread.currentThread().stackTrace
            val sb = StringBuilder("TRUEWATCH #$n $tag stack:")
            for (i in 0 until minOf(st.size, 25)) {
                val s = st[i]
                sb.append("\n  at ").append(s.className).append(".").append(s.methodName)
                    .append("(").append(s.fileName).append(":").append(s.lineNumber).append(")")
            }
            Logger.d(sb.toString())
        } catch (_: Throwable) {}
    }
    // ★ LAFIND：脏 QPhoto 身份反查真源字段——BFS 遍历 VM+adapter 对象图（深度 6），
    // 找出「哪些字段的 List/数组以身份相同包含该元素」，命中即 allowEmpty 清理。
    // 根集：vmRef（VM 数据源）+ adpRef/adpRefs（pager adapter——rerank 把直播插进
    // adapter 数据集，不在 VM 真源里，漏拦实证 2026-09-08）。
    // 后台线程跑（ANR 红线），2.5 秒节流
    private fun laFind(force: Boolean = false) {
        if (liveTop) return
        val now = System.currentTimeMillis()
        if (!force && now - laFindAt < 2500) return
        laFindAt = now
        val targets = synchronized(retDelQp) { retDelQp.toList().filterNotNull() }
        // ★ force（rerank.d.j 预判前方有直播触发）时 targets 空也扫：直播卡可能未经
        // filterResult 快照（retDelQp 空），靠 laElDirty 直判 QPhoto(LiveStreamFeed) 命中
        if (targets.isEmpty() && !force) return
        val roots = mutableListOf<Pair<Any, String>>()
        vmRef?.let { roots.add(it to "VM.") }
        adpRef?.let { roots.add(it to "ADP.") }
        for (ar in adpRefs.toList()) roots.add(ar to "ADP2.")
        if (roots.isEmpty()) return
        cleanExecutor.execute {
            Logger.safe("laFind") {
                val seen = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
                fun walk(holder: Any, path: String, depth: Int) {
                if (depth > 6) return
                if (!seen.add(System.identityHashCode(holder))) return
                var c: Class<*>? = holder.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 2) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        // 系统结构字段黑名单：lifecycle/Fragment 管理/拦截器列表绝不碰
                        val fn0 = f.name
                        if (fn0.contains("Lifecycle") || fn0.contains("FragmentManager") ||
                            fn0 == "mAdded" || fn0.contains("Interceptor") || fn0.contains("Callbacks")) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(holder) ?: continue
                            val npath = "$path${f.name}."
                            when (v) {
                                is List<*> -> {
                                    var hitCnt = 0
                                    for (el in v) {
                                        // 身份命中（retDelQp 已知脏）或直接判脏（rerank 插进
                                        // adapter 的脏项没经过 filterResult，不在 targets 里）
                                        if (el != null && (targets.any { it === el } || laElDirty(el))) {
                                            hitCnt++
                                            Logger.d("LAFIND HIT $path${f.name}[${v.indexOf(el)}] size=${v.size} el=${el.javaClass.name}")
                                        }
                                    }
                                    // ★ 命中即清：所有含脏项的 List 一律即时 sanitize；
                                    // 凡 VM.* 下的脏列表一律挂 TRUEWATCH 前置删除（per-cls
                                    // 去重，同运行时类的列表共享同一组 hook，身份比对过滤）
                                    if (hitCnt > 0) {
                                        if (path.startsWith("VM.") && trueListRefs.none { it === v } &&
                                            trueWatchIds.add(System.identityHashCode(v))) {
                                            if (truesrcCapDiag < 20) {
                                                truesrcCapDiag++
                                                Logger.always("TRUESRC captured: $path${f.name} id=${System.identityHashCode(v)} size=${v.size} cls=${v.javaClass.name}")
                                            }
                                            armTrueWatch(v)
                                        }
                                        @Suppress("UNCHECKED_CAST")
                                        val mut = v as? MutableList<Any?>
                                        if (mut != null) {
                                            val bs = mut.size
                                            // 真源存储列表：允许删空（pager 渲染走 V0 快照聚合，
                                            // 存储列表删空不崩；残留 1 项由 size>1 保护留脏）
                                            sanitizeList(mut, "lafind:$path${f.name}", allowEmpty = true)
                                            if (mut.size != bs) Logger.d("LAFIND sanitize $path${f.name}: removed=${bs - mut.size} left=${mut.size}")
                                        }
                                    }
                                    if (depth < 6) for (el in v) if (el != null && !el.javaClass.name.startsWith("java.")) walk(el, npath + "[].", depth + 1)
                                }
                                is Array<*> -> {
                                    for (el in v) {
                                        if (el != null && (targets.any { it === el } || laElDirty(el))) {
                                            Logger.d("LAFIND HIT $path${f.name}[] size=${v.size}")
                                        }
                                    }
                                    if (depth < 6) for (el in v) if (el != null && !el.javaClass.name.startsWith("java.")) walk(el, npath + "[].", depth + 1)
                                }
                                else -> {
                                    val vn = v.javaClass.name
                                    if (!vn.startsWith("java.") && !vn.startsWith("android.") && v !is android.view.View) {
                                        walk(v, npath, depth + 1)
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
            }
                for ((r0, p0) in roots) walk(r0, p0, 0)
                Logger.d("LAFIND done targets=${targets.size} roots=${roots.size}")
            }
        }
    }


    private fun hookFeedResponse(xp: XposedInterface, cl: ClassLoader) {
        val respNames = arrayOf(
            "com.yxcorp.gifshow.detail.slideplay.hotphoto.network.SlideHotPhotoResponse",
            "com.yxcorp.gifshow.feed.response.PhotoResponse",
            "com.yxcorp.gifshow.detail.fragments.milano.commonfeedslide.network.feed.CommonFeedSlideResponse"
        )
        for (rn in respNames) {
            val rc = Reflect.findClass(rn, cl) ?: continue
            Logger.d("hookFeedResp: $rn")
            var c: Class<*>? = rc
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    Logger.d("  resp field: ${f.name} type=${f.type.name}")
                }
                for (m in c.declaredMethods) {
                    val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                    if (isRetList || hasListParam) {
                        Logger.d("  resp method: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName} static=${java.lang.reflect.Modifier.isStatic(m.modifiers)}")
                        Logger.safe("hookResp.${rn}.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("resp.${rn}.${m.name}").intercept { chain ->
                                try { filterListArgs(chain.args) } catch (_: Throwable) {}
                                val result = chain.proceed()
                                try { filterResult(result) } catch (_: Throwable) {}
                                try { filterResponseFields(chain.thisObject) } catch (_: Throwable) {}
                                result
                            }
                        }
                    }
                }
                c = c.superclass; lvl++
            }
        }
    }

    private fun startTrack(act: Activity) {
        tracked = act
        handler.removeCallbacks(checkTask)
        handler.postDelayed(checkTask, 300)
    }

    private fun stopTrack(act: Activity) {
        if (tracked === act) {
            tracked = null
            handler.removeCallbacks(checkTask)
        }
    }

    private val checkTask = object : Runnable {
        override fun run() {
            val act = tracked ?: return
            check(act)
            if (tracked != null) handler.postDelayed(this, 350)
        }
    }

    private var lastPagerSearch = 0L
    private fun check(act: Activity) {
        Logger.safe("findPagerInCheck") {
            val now = System.currentTimeMillis()
            if (now - lastPagerSearch > 5000) {
                lastPagerSearch = now
                try {
                    val id = act.resources.getIdentifier("nasa_groot_view_pager", "id", "com.smile.gifmaker")
                    if (id != 0) {
                        val v: View? = act.findViewById(id)
                        if (v != null) findPager(v)
                    }
                } catch (_: Throwable) {}
                val pc = pagerCache
                if (pc == null || (pc as? android.view.View)?.isAttachedToWindow != true) {
                    val decor = act.window.decorView as? ViewGroup
                    if (decor != null) findPager(decor)
                }
            }
        }

        Logger.safe("filterMetaCheck") {

            val now = System.currentTimeMillis()
            if (now - lastSkipTime < 10000) return@safe
            lastSkipTime = now
            val v = CurrentVideo.current
            if (v.valid() && shouldFilterMeta(v) && !Logger.quiet) Logger.d("filter meta diag: ${readCaption(v)?.take(20)}")
        }
    }


    // 从 View 向上爬（含反射字段），找类名含 QPhoto 的对象；命中提示 View 所在
    // item 的数据绑定对象（holder/binding）必然持有 QPhoto 引用
    private fun findQpUpFromView(v: View, depth: Int): Any? {
        if (depth > 24) return null
        val tag = v.tag
        if (tag != null && tag !is Int && tag.javaClass.name.contains("QPhoto")) return tag
        var c: Class<*>? = v.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 6) {
            for (f in c!!.declaredFields) {
                if (f.type.isPrimitive || f.type == String::class.java) continue
                try {
                    f.isAccessible = true
                    val fv = f.get(v) ?: continue
                    if (fv.javaClass.name.contains("QPhoto")) return fv
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        val p = v.parent
        return if (p is View) findQpUpFromView(p, depth + 1) else null
    }

    @Volatile private var lastViewQp: Any? = null
    private var aiFullDumpCount = 0
    // 全字段 dump（不限字段名）：qp/ent/cm/pm/corona + ext map entries，跳过 null/默认值，分批打日志
    private fun dumpAiAllFields(qp: Any) {
        aiFullDumpCount++
        Logger.always("AIFULL #$aiFullDumpCount start qp=${qp.javaClass.name}")
        val ent = try { Reflect.readAny(qp, "mEntity") } catch (_: Throwable) { null }
        val roots = mutableListOf("qp" to qp)
        if (ent != null) {
            roots.add("ent" to ent)
            for (fn in arrayOf("mCommonMeta", "mPhotoMeta", "mCoronaInfo")) {
                try { Reflect.readAny(ent, fn)?.let { roots.add(fn to it) } } catch (_: Throwable) {}
            }
        }
        for ((tag, root) in roots) {
            var c: Class<*>? = root.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                val fs = c!!.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                val sb = StringBuilder("$tag L$lvl ${c.simpleName}:")
                var n = 0
                for (f in fs) {
                    try {
                        f.isAccessible = true
                        val v = f.get(root) ?: continue
                        if (v is java.util.Collection<*> && v.isEmpty()) continue
                        if (v is Map<*, *> && v.isEmpty()) continue
                        val s = v.toString()
                        if (s.isBlank() || s == "false" || s == "0" || s == "0.0") continue
                        sb.append(" [${f.name}]=").append(s.take(64))
                        n++
                        if (n >= 26) break
                    } catch (_: Throwable) {}
                }
                if (n > 0) Logger.always("AIFULL $sb")
                c = c.superclass; lvl++
            }
        }
        // metaExtContainer / mExtraMap：快手常用 ext map 携带标记，逐 entry 展开（value 对象再展开一层字段）
        if (ent != null) {
            for (tag in arrayOf("metaExtContainer", "mExtraMap", "mExtData")) {
                val em = try { Reflect.readAny(ent, tag) } catch (_: Throwable) { null } ?: continue
                if (em is Map<*, *>) {
                    for ((k, v) in em.entries) {
                        if (v == null) continue
                        if (v is Map<*, *> || v is java.util.Collection<*>) {
                            Logger.always("AIFULL map[$tag] $k=${v.toString().take(80)}")
                        } else {
                            val sb = StringBuilder("AIFULL map[$tag] $k=${v.javaClass.simpleName}:")
                            var c: Class<*>? = v.javaClass
                            var n = 0
                            while (c != null && c != Any::class.java && n < 3) {
                                for (f in c.declaredFields) {
                                    try {
                                        f.isAccessible = true
                                        val fv = f.get(v) ?: continue
                                        val s = fv.toString()
                                        if (s.isBlank() || s == "false" || s == "0" || s == "0.0") continue
                                        sb.append(" [${f.name}]=").append(s.take(40)); n++
                                        if (n >= 12) break
                                    } catch (_: Throwable) {}
                                }
                                c = c.superclass
                            }
                            Logger.d(sb.toString())
                        }
                    }
                }
            }
        }
        Logger.always("AIFULL #$aiFullDumpCount end")
    }



    // ==================== 数据层拦�?====================

    private fun hookNasaFragment(xp: XposedInterface, cl: ClassLoader) {
        val c = Reflect.findClass("com.yxcorp.gifshow.detail.slideplay.nasa.groot.vm.NasaPhotoDetailFragment", cl) ?: return
        val m = Reflect.findMethod(c, "onResume", 0) ?: return
        Logger.safe("hookNasa") {
            Logger.d("nasaCls: ${c.name} loader=${c.classLoader} methodCls=${m.declaringClass.name}")
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("nasa.onResume").intercept { chain ->
                chain.proceed()
                try {
                    Logger.d("nasaCls real: ${chain.thisObject.javaClass.name} loader=${chain.thisObject.javaClass.classLoader}")
                    realFragClass = chain.thisObject.javaClass
                    hookFragCallSeq(xp, realFragClass!!)
                } catch (_: Throwable) {}
                try { findDataSource(chain.thisObject) } catch (_: Throwable) {}
                fragRef = chain.thisObject
                handler.postDelayed({ try { diagFragment(chain.thisObject) } catch (_: Throwable) {} }, 500)
                null
            }
        }
        hookFragQpSetters(xp, c)
    }
    @Volatile private var realFragClass: Class<*>? = null
    private val fragSeqHookedClasses = mutableSetOf<String>()
    private val fragSeqCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var gqDumpCount = 0

    private fun hookFragCallSeq(xp: XposedInterface, fragClass: Class<*>) {
        val clsKey = fragClass.name + "@" + System.identityHashCode(fragClass.classLoader)
        val isNew = synchronized(fragSeqHookedClasses) { fragSeqHookedClasses.add(clsKey) }
        if (!isNew) return
        var hookOk = 0
        Logger.d("fragSeqInstall start: ${fragClass.name}")
        var cls: Class<*>? = fragClass
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 4) {
            for (m in cls!!.declaredMethods) {
                if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                if (m.parameterTypes.isEmpty()) continue
                if (m.name == "onResume" || m.name == "onPause") continue
                Logger.safe("hookFragSeq.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("fragSeq.${cls!!.name}.${m.name}").intercept { chain ->
                        try {
                            val n = fragSeqCount.incrementAndGet()
                            if (n <= 50) {
                                val argsDesc = chain.args.joinToString(",") { a -> a?.javaClass?.simpleName ?: "null" }.take(120)
                                Logger.d("fragSeq #$n ${m.name}($argsDesc) in ${cls!!.simpleName}")
                            }
                            if (m.name == "gq" || m.name == "aq" || m.name == "Vp") {
                                val a0 = chain.args.firstOrNull()
                                if (a0 != null) {
                                    if (m.name == "aq") {
                                        try {
                                            vmRef = a0
                                            filterVmLists(a0)
                                        } catch (_: Throwable) {}
                                    }
                                    val qpFound = findQpInObject(a0)
                                    val qpHit = qpFound?.let { shouldFilterFeed(it) } == true
                                    if (gqDumpCount < 8) {
                                        gqDumpCount++
                                        Logger.d("fragArg ${m.name}: cls=${a0.javaClass.name} qpIn=${qpFound != null} qpHit=$qpHit")
                                    }
                                    // ★ Vp 是 Fragment 绑定方法（实证 HomeFeaturedMilanoContainerFragment 经 Vp 上屏），
                                    // 参数里的 Fragment 包含脏 QPhoto（qpHit=true）→ 不 proceed = 不绑定 = 直播卡不上屏
                                    if (m.name == "Vp" && qpHit) {
                                        Logger.always("Vp BLOCKED: ${a0.javaClass.name}")
                                        return@intercept null
                                    }

                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
                hookOk++
            }
            cls = cls.superclass; lvl++
        }
        Logger.d("fragSeqInstall done: ok=$hookOk candidates=$hookOk")
    }


    private val fragSetterHooked = mutableSetOf<String>()
    private fun hookFragQpSetters(xp: XposedInterface, fragClass: Class<*>) {
        var cls: Class<*>? = fragClass
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 5) {
            for (m in cls!!.declaredMethods) {
                if (!m.parameterTypes.any { it.name.contains("QPhoto") }) continue
                val key = "${cls.name}.${m.name}"
                val shouldHook = synchronized(fragSetterHooked) { fragSetterHooked.add(key) }
                if (!shouldHook) continue
                Logger.d("hook frag qp setter: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) in ${cls.name}")
                Logger.safe("hookFragSet.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("fragSet.${key}").intercept { chain ->
                        try {
                            for (i in chain.args.indices) {
                                val a = chain.args[i] ?: continue
                                if (qpClassRef?.isAssignableFrom(a.javaClass) == true && shouldFilterFeed(a)) {
                                    val clean = findCleanQp()
                                    if (clean != null) {
                                        Logger.d("fragSet ${m.name} replaced: ${readCaption(a)?.take(15)} -> ${readCaption(clean)?.take(15)}")
                                        chain.args[i] = clean
                                    } else {
                                        Logger.d("fragSet ${m.name} hit but no clean: ${readCaption(a)?.take(15)}")
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
            }
            cls = cls.superclass; lvl++
        }
    }

    @Volatile private var inFindClean = false
    private var lastClean: Any? = null
    private var vmReplacedDiag = 0
    private var offerDiag = 0
    private var findCleanDiag = 0
    private var vmRetNameDiag = 0
    private var vmKeepDiag = 0
    private val cleanQueue = ArrayDeque<Any>()
    private fun offerClean(qp: Any) {
        val ent = Reflect.readAny(qp, "mEntity")
        if (ent == null) { if (offerDiag < 20) { offerDiag++; Logger.d("offer skip: noEntity") }; return }
        if (!ent.javaClass.name.contains("feed.VideoFeed")) {
            if (offerDiag < 20) { offerDiag++; Logger.d("offer skip: nonVF ${ent.javaClass.simpleName}") }
            return
        }
        if (Reflect.readAny(ent, "mPhotoMeta") == null) { if (offerDiag < 20) { offerDiag++; Logger.d("offer skip: noMeta") }; return }
        if (cleanQueue.any { it === qp }) return
        if (cleanQueue.size >= 12) cleanQueue.removeFirst()
        cleanQueue.add(qp)
        // 持久缓存：跨窗口不排空，兜底替换�?
        synchronized(cleanCachePersist) {
            if (cleanCachePersist.none { it === qp }) {
                if (cleanCachePersist.size >= 60) cleanCachePersist.removeFirst()
                cleanCachePersist.addLast(qp)
            }
        }
        recordCleanUrl(ent)
        if (offerDiag < 30) { offerDiag++; Logger.d("offer add: ${readCaption(qp)?.take(14)} queue=${cleanQueue.size}") }
    }

    private val cleanCachePersist = ArrayDeque<Any>()

    private val dirtyUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val cleanUrlPool = ArrayDeque<String>()
    private val hookedVmUrlClasses = mutableSetOf<String>()
    private var urlSubCount = 0

    private fun readVideoUrl(ent: Any): String? {
        return try {
            val vm = Reflect.readAny(ent, "mVideoModel") ?: return null
            Reflect.readString(vm, "mVideoUrl")
        } catch (_: Throwable) { null }
    }

    private fun recordCleanUrl(ent: Any) {
        val url = readVideoUrl(ent) ?: return
        synchronized(cleanUrlPool) {
            if (cleanUrlPool.none { it == url }) {
                if (cleanUrlPool.size >= 30) cleanUrlPool.removeFirst()
                cleanUrlPool.addLast(url)
            }
        }
    }

    private fun recordDirtyUrl(ent: Any) {
        val url = readVideoUrl(ent) ?: return
        if (url.isBlank()) return
        dirtyUrls.add(url)
        if (dirtyUrls.size > 300) dirtyUrls.clear()
        try {
            val vm = Reflect.readAny(ent, "mVideoModel") ?: return
            hookVideoModelClass(vm.javaClass)
        } catch (_: Throwable) {}
        hookPlayerClasses()
    }

    private var playerHookTried = false
    private fun hookPlayerClasses() {
        if (playerHookTried) return
        playerHookTried = true
        val xp = xpRef ?: return
        val appCl = qpClassRef?.classLoader ?: vmRef?.javaClass?.classLoader ?: return
        for (cn in listOf(
            "com.kwai.video.player.KwaiMediaPlayerWrapper",
            "com.kwai.video.player.KwaiMediaPlayerImplV3",
            "com.kwai.video.player.KwaiMediaPlayerImpl",
            "com.yxcorp.gifshow.media.player.PhotoDetailPlayer"
        )) {
            val c = try { Class.forName(cn, false, appCl) } catch (_: Throwable) { null } ?: continue
            Logger.d("player hook class: ${c.name}")
            for (m in c.declaredMethods) {
                if (m.returnType != Void.TYPE || m.parameterTypes.isEmpty() || m.parameterTypes[0] != String::class.java) continue
                val nm = m.name.lowercase()
                if (!(nm.contains("datasource") || nm.contains("videopath") || nm.contains("videouri") || nm.contains("setdata") || nm.contains("loadurl") || nm.contains("playurl"))) continue
                try {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("player.${c.name}.${m.name}").intercept { chain ->
                        try {
                            val a0 = chain.args.getOrNull(0)
                            if (a0 is String && a0.isNotBlank()) {
                                val dirty = dirtyUrls.any { a0.startsWith(it) || it.startsWith(a0) }
                                if (dirty) {
                                    val clean = synchronized(cleanUrlPool) { cleanUrlPool.firstOrNull { it != a0 } }
                                    if (clean != null) {
                                        if (urlSubCount < 20) { urlSubCount++; Logger.d("player ${m.name} -> clean: ${a0.take(36)}") }
                                        chain.args[0] = clean
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                        null
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun hookVideoModelClass(c: Class<*>) {
        val xp = xpRef ?: return
        synchronized(hookedVmUrlClasses) { if (!hookedVmUrlClasses.add(c.name)) return }
        for (m in c.methods) {
            if (m.returnType != String::class.java || m.parameterTypes.isNotEmpty()) continue
            val nm = m.name.lowercase()
            if (!(nm.contains("url") || nm.contains("play") || nm.contains("video") || nm.contains("address"))) continue
            try {
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vmurl.${c.name}.${m.name}").intercept { chain ->
                    val r = chain.proceed()
                    try {
                        if (r is String && r.isNotBlank()) {
                            val dirty = dirtyUrls.any { r.startsWith(it) || it.startsWith(r) }
                            if (dirty) {
                                val clean = synchronized(cleanUrlPool) { cleanUrlPool.firstOrNull { it != r } }
                                if (clean != null) {
                                    if (urlSubCount < 20) { urlSubCount++; Logger.d("vm url ${m.name} -> clean: ${r.take(36)}") }
                                    return@intercept clean
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                    r
                }
            } catch (_: Throwable) {}
        }
    }
    private fun pickFromQueue(): Any? {
        val qpClass = qpClassRef ?: return null
        var idx = 0
        while (cleanQueue.isNotEmpty() && idx < 24) {
            val head = cleanQueue.removeFirst()
            idx++
            if (qpClass.isAssignableFrom(head.javaClass) && !shouldFilterFeed(head)) {
                cleanQueue.add(head)
                if (head !== lastClean || cleanQueue.size == 1) {
                    lastClean = head
                    return head
                }
            }
        }
        // 持久缓存兜底
        synchronized(cleanCachePersist) {
            for (c in cleanCachePersist) {
                if (qpClass.isAssignableFrom(c.javaClass) && !shouldFilterFeed(c) && c !== lastClean) {
                    lastClean = c
                    return c
                }
            }
        }
        return null
    }
    private fun findCleanQp(): Any? {
        if (inFindClean) return null
        val vm = vmRef
        if (vm == null) return null
        pickFromQueue()?.let { return it }
        // 轻量补充：仅�?VM 窗口字段 i（不�?T0/U0，避免反射副作用/异常�?
        inFindClean = true
        try {
            val i = try { Reflect.readAny(vm, "i") } catch (_: Throwable) { null }
            if (i is List<*>) {
                for (el in i) {
                    val q = el?.let { findQpInObject(it) }
                    if (q != null && !shouldFilterFeed(q)) offerClean(q)
                }
            }
        } finally { inFindClean = false }
        return pickFromQueue()
    }

    private fun writeQpInto(obj: Any, cleanQp: Any): Int {
        // 统一保护：目标对象当前持 LiveStreamFeed 时拒绝写入 VideoFeed，
        // 否则快手 onMeasure 会 ClassCastException(VideoFeed→LiveStreamFeed)。
        val targetType = try { Reflect.readAny(obj, "mEntity")?.javaClass?.name } catch (_: Throwable) { null }
        if (targetType?.contains("LiveStreamFeed") == true) return 0
        val cleanType = try { Reflect.readAny(cleanQp, "mEntity")?.javaClass?.name } catch (_: Throwable) { null }
        if (cleanType?.contains("LiveStreamFeed") == true) return 0
        val qpClass = qpClassRef ?: return 0
        var swapped = 0
        val visited = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
        val queue = ArrayDeque<Any>()
        queue.add(obj); visited.add(obj)
        var depth = 0
        while (queue.isNotEmpty() && depth < 4) {
            val sz = queue.size
            for (n in 0 until sz) {
                val o = queue.removeFirst()
                var c: Class<*>? = o.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(o)
                            if (qpClass.isAssignableFrom(f.type)) {
                                f.set(o, cleanQp); swapped++
                            } else if (v != null && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.contains("Fragment") && visited.add(v)) {
                                queue.add(v)
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
            }
            depth++
        }
        return swapped
    }

    private fun dataSwallow(reason: String) {
        if (dataDiag < 40) { dataDiag++; Logger.d("DATA skip $reason") }
    }

    private fun safeNextLong(o: Any?, name: String): Long {
        return try { o?.let { Reflect.readLong(it, name) } ?: -1L } catch (_: Throwable) { -1L }
    }

    private fun dumpAiFields(qp: Any, ent: Any, cm: Any?): String {
        val sb = StringBuilder()
        var n = 0
        fun put(tag: String, fn: String, v: Any?) {
            if (v != null && n < 22) { sb.append(",").append(tag).append(".").append(fn).append("=").append(v.toString().take(12)); n++ }
        }
        // 实体 + PhotoMeta + QPhoto
        for (o in listOf(ent, cm, qp)) {
            if (o == null) continue
            var c: Class<*>? = o.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    val fn = f.name
                    if ((fn.contains("ai", true) || fn.contains("aigc") || fn.contains("gen", true)) && !fn.contains("gain") && !fn.contains("again")) {
                        try { f.isAccessible = true; put("e", fn, f.get(o)) } catch (_: Throwable) {}
                    }
                }
                c = c.superclass; lvl++
            }
        }
        // mVideoModel
        val vm = try { Reflect.readAny(ent, "mVideoModel") } catch (_: Throwable) { null }
        if (vm != null) {
            var c: Class<*>? = vm.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    if ((f.name.contains("ai", true) || f.name.contains("aigc") || f.name.contains("gen", true)) && !f.name.contains("gain")) {
                        try { f.isAccessible = true; put("vm", f.name, f.get(vm)) } catch (_: Throwable) {}
                    }
                }
                c = c.superclass; lvl++
            }
        }
        // mCoronaInfo 内部（快�?AI 生成内容标识体系�?
        val cor = try { Reflect.readAny(ent, "mCoronaInfo") } catch (_: Throwable) { null }
        if (cor != null) {
            var c: Class<*>? = cor.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(cor)
                        if (v != null) put("cor", f.name, v)
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
        // ExtendableModelMap / 动�?map �?
        for (tag in listOf("metaExtContainer", "mExtraMap", "mExtData")) {
            val em = try { Reflect.readAny(ent, tag) } catch (_: Throwable) { null } ?: continue
            if (em is Map<*, *>) {
                for ((k, v) in em.entries) {
                    val ks = k.toString()
                    if (ks.contains("ai", true) || ks.contains("gen", true)) put("map[" + tag + "]", ks, v)
                }
            } else {
                // �?Map：枚举其字段里值非空的小写�?
                var c: Class<*>? = em.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    for (f in c!!.declaredFields) {
                        if (f.name.contains("ai", true) && !f.name.contains("gain")) {
                            try { f.isAccessible = true; put("em", f.name, f.get(em)) } catch (_: Throwable) {}
                        }
                    }
                    c = c.superclass; lvl++
                }
            }
        }
        return if (sb.isEmpty()) "none" else sb.toString().removePrefix(",")
    }

    private fun readUserName(qp: Any, ent: Any): String {
        try {
            val cm = Reflect.readAny(ent, "mPhotoMeta")
            cm?.let { c ->
                Reflect.readString(c, "mUserName")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val u = Reflect.readAny(ent, "mUser")
            u?.let { obj ->
                Reflect.readString(obj, "mName")?.takeIf { it.isNotBlank() }?.let { return it }
                Reflect.readString(obj, "mUserName")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val uq = Reflect.readAny(qp, "mUser")
            uq?.let { obj ->
                Reflect.readString(obj, "mName")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            Reflect.readString(qp, "mUserName")?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Throwable) {}
        return ""
    }

    // 影视/广告壳子字段对普通视频也是非 null 空壳 �?必须查内层真实内容才算命�?
    private fun dramaShellReal(ent: Any): Boolean {
        try {
            val serial = Reflect.readAny(ent, "mStandardSerialMeta")
            if (serial != null) {
                if (safeNextLong(serial, "mSerialId") > 0) return true
                if (safeNextLong(serial, "mDramaId") > 0) return true
                val t = Reflect.readString(serial, "mTitle")
                if (t != null && t.isNotBlank()) return true
                val dm = Reflect.readAny(serial, "dataMap")
                if (dm is Map<*, *> && dm.isNotEmpty()) return true
            }
            val column = Reflect.readAny(ent, "mColumnMeta")
            if (column != null) {
                if (safeNextLong(column, "mColumnId") > 0) return true
                val t = Reflect.readString(column, "mColumnTitle")
                if (t != null && t.isNotBlank()) return true
            }
            val adNovel = Reflect.readAny(ent, "mAdNovelVideoMeta")
            if (adNovel != null) {
                if (safeNextLong(adNovel, "mNovelId") > 0) return true
                val t = Reflect.readString(adNovel, "mTitle")
                if (t != null && t.isNotBlank()) return true
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun captureFeedItem(qp: Any) {
        try {
            val id = System.identityHashCode(qp)
            if (capturedIds.contains(id)) return
            if (capturedIds.size >= 400) capturedIds.clear()
            capturedIds.add(id)
            if (capturedLines >= 1500) return
            capturedLines++
            val ent = Reflect.readAny(qp, "mEntity") ?: run { dataSwallow("noEnt"); return }
            val cm = Reflect.readAny(ent, "mPhotoMeta")
            val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
            val user = cm?.let { Reflect.readString(it, "mUserName") } ?: ""
            val type = cm?.let { Reflect.readLong(it, "mType") } ?: -1L
            val like = cm?.let { Reflect.readLong(it, "mLikeCount") } ?: -1L
            val cmt = cm?.let { Reflect.readLong(it, "mCommentCount") } ?: -1L
            val ai = cm?.let { Reflect.readBool(it, "photoAiAnalyze") } ?: false
            val live = Reflect.readAny(ent, "mLivePlaybackMeta")
            val sid = live?.let { Reflect.readAny(it, "mLiveStreamId")?.toString() } ?: ""
            val mAd = Reflect.readAny(ent, "mAd")
            val nativeD = Reflect.readAny(ent, "mKwAppNativeDrama")
            val novel = Reflect.readAny(ent, "mNovelDrama")
            val ltos = Reflect.readAny(ent, "mLongToShortDrama")
            val serial = Reflect.readAny(ent, "mStandardSerialMeta")
            val column = Reflect.readAny(ent, "mColumnMeta")
            val adNovel = Reflect.readAny(ent, "mAdNovelVideoMeta")
            val tube = Reflect.readAny(ent, "mTubeModel")
            val tubeInfo = tube?.let { Reflect.readAny(it, "mTubeInfo") } != null
            val tubeTag = tube?.let { Reflect.readBool(it, "mHasTubeTag") } ?: false
            val vm = Reflect.readAny(ent, "mVideoModel")
            val vid = vm?.let { Reflect.readAny(it, "mVideoUrl") } != null
            val longVid = vm?.let { Reflect.readBool(it, "mIsLongVideo") } ?: false
            val idx = vm?.let { Reflect.readAny(it, "mIndex") } != null
            val comm = cm?.let { Reflect.readAny(it, "mCommodityJumpUrl") } != null
            val kwApp = Reflect.readAny(ent, "mKwAppMeta") != null
            val atlasT = cm?.let { Reflect.readAny(it, "mAtlasDetailTitle") } != null
            val atlasText = cm?.let { Reflect.readBool(it, "mHasAtlasText") } ?: false
            val living = cm?.let { Reflect.readBool(it, "mCurrentLivingState") } ?: false
            val eid = ent.javaClass.name.substringAfterLast('.')
            val entType = safeNextLong(ent, "mFeedType")
            val entDisp = safeNextLong(ent, "mDisplayType")
            val qpType = safeNextLong(qp, "mType")
            val pmCls = cm?.javaClass?.simpleName ?: "null"
            val adCls = mAd?.javaClass?.simpleName ?: ""
            val capEsc = cap.replace('\n', ' ').take(38)
            Logger.d("DATA ent=$eid pm=$pmCls cap=\"$capEsc\" user=$user type=$type like=$like cmt=$cmt ai=$ai live=${live != null} sid=$sid living=$living adCls=$adCls nativeD=${nativeD != null} novel=${novel != null} ltos=${ltos != null} serial=${serial != null} column=${column != null} adNovel=${adNovel != null} tubeI=$tubeInfo tubeT=$tubeTag vid=$vid long=$longVid idx=$idx comm=$comm kwApp=$kwApp atlasT=$atlasT atlasText=$atlasText entType=$entType entDisp=$entDisp qpType=$qpType")
            // 直播卡：dump 全部字段找特�?
            if (ent.javaClass.name.contains("LiveStreamFeed")) {
                val ik = System.identityHashCode(ent)
                if (liveDumped.add(ik)) {
                    if (liveDumpCount < 60) {
                        liveDumpCount++
                        Logger.d("LIVEDUMP ${dumpKV(ent)}")
                    }
                }
            }
            // 广告/影视卡：dump 内层标题元数�?
            if (serial != null || column != null || adNovel != null || nativeD != null || ltos != null) {
                val ik = System.identityHashCode(ent)
                if (dramaDumped.add(ik)) {
                    if (dramaDumpCount < 100) {
                        dramaDumpCount++
                        val sb = StringBuilder("DRAMADUMP cap=\"$capEsc\"")
                        if (serial != null) {
                            val dm = Reflect.readAny(serial, "dataMap")
                            val sId = safeNextLong(serial, "mSerialId")
                            val dId = safeNextLong(serial, "mDramaId")
                            val sTitle = Reflect.readString(serial, "mTitle")
                            val ep = safeNextLong(serial, "mEpisodeCount")
                            val play = safeNextLong(serial, "mPlayCount")
                            val dmSize = if (dm is Map<*, *>) dm.size else if (dm is Collection<*>) dm.size else -1
                            sb.append(" serial{dataMapSize=$dmSize;mSerialId=$sId;mDramaId=$dId;mTitle=$sTitle;ep=$ep;play=$play;vars=").append(dumpKV(serial)).append("}")
                        }
                        if (column != null) {
                            val cId = safeNextLong(column, "mColumnId")
                            val cTitle = Reflect.readString(column, "mColumnTitle")
                            sb.append(" column{mColumnId=$cId;mColumnTitle=$cTitle;vars=").append(dumpKV(column)).append("}")
                        }
                        if (adNovel != null) {
                            val nId = safeNextLong(adNovel, "mNovelId")
                            val nTitle = Reflect.readString(adNovel, "mTitle")
                            val nType = safeNextLong(adNovel, "mAdType")
                            sb.append(" adNovel{mNovelId=$nId;mTitle=$nTitle;mAdType=$nType;vars=").append(dumpKV(adNovel)).append("}")
                        }
                        if (nativeD != null) sb.append(" nativeD=").append(dumpKV(nativeD))
                        if (ltos != null) sb.append(" ltos=").append(dumpKV(ltos))
                        if (mAd != null) sb.append(" ad=").append(dumpKV(mAd))
                        Logger.d(sb.toString())
                    }
                }
            }
        } catch (t: Throwable) { dataSwallow("err ${t.javaClass.simpleName}") }
    }

    @Volatile private var dataDiag = 0
    private var capturedLines = 0
    private var liveDumpCount = 0
    private var dramaDumpCount = 0
    private val liveDumped = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
    private val dramaDumped = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
    private val capturedIds = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())

    private var lastForceRebind = 0L
    private var pagerCache: Any? = null
    private var holderDumpCount = 0
    private var vmGetSubCount = 0
    private var vmMethodDump = 0

    // dump holder 图：�?fragment / photo 绑定字段（深�?�?
    private fun dumpHolderGraph(o: Any, depth: Int) {
        if (depth > 2) return
        try {
            var c: Class<*>? = o.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    try {
                        f.isAccessible = true
                        val v = f.get(o)
                        if (v == null) continue
                        val vn = v.javaClass.simpleName
                        val vcap = if (vn.contains("Photo") || vn.contains("Feed")) { try { readCaption(v)?.take(14) ?: "" } catch (_: Throwable) { "?" } } else ""
                        Logger.d("holderF[$depth] ${f.name} : $vn ${if (vcap.isNotEmpty()) "cap=$vcap" else ""}")
                        if (depth < 2 && !vn.contains("Fragment") && (vn.contains("Photo") || vn.contains("Holder") || vn.contains("Feed") || vn.contains("Model") || vn.contains("Meta"))) {
                            dumpHolderGraph(v, depth + 1)
                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        } catch (_: Throwable) {}
    }
    private var milanoLastDump = 0L
    private fun dumpMilanoHolder(o: Any, pos: Int) {
        val now = System.currentTimeMillis()
        if (now - milanoLastDump < 5000) return
        milanoLastDump = now
        try {
            Logger.d("MILANOHOLDER pos=$pos cls=${o.javaClass.name}")
            val visited = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
            var lines = 0
            fun dump(obj: Any, depth: Int, prefix: String) {
                if (depth > 3 || lines > 60) return
                var c: Class<*>? = obj.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 4 && lines <= 60) {
                    for (f in c!!.declaredFields) {
                        if (lines > 60) break
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(obj) ?: continue
                            val vn = v.javaClass.name
                            lines++
                            var s = prefix + f.name + " : " + vn.substringAfterLast('.')
                            if (v is String || v is Number || v is Boolean) s += " = " + v.toString().take(28)
                            Logger.d("  MHO$depth $s")
                            if (depth < 3 && !vn.startsWith("java.") && !vn.startsWith("android.") && !vn.startsWith("kotlin.") && visited.add(v)) {
                                dump(v, depth + 1, prefix + "  ")
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
            }
            dump(o, 0, "")
        } catch (t: Throwable) { Logger.d("MILANOHOLDER err: ${t.javaClass.simpleName}") }
    }
    // 当前页命中：原地 setCurrentItem(同位�?false) 强制重实例化——页面不移动，仅内容换干净
    private fun forceRebindCurrent(pos: Int) {
        try {
            val pager = pagerCache ?: return
            val cur = Reflect.readInt(pager, "mCurrentItem")
            val relCur = if (cur >= 1000000) cur % 1000000 else cur
            if (relCur != pos && pos != relCur) return
            Logger.d("adpGet forceRebind try cur=$cur rel=$relCur pos=$pos")
            val now = System.currentTimeMillis()
            if (now - lastForceRebind < 1200) return
            lastForceRebind = now
            // 先通知数据集变更（feed 适配器常覆写 getItemPosition=POSITION_NONE �?触发当前页重建）
            val adp = adpRef
            if (adp != null) { try { Reflect.callMethod(adp, "notifyDataSetChanged") } catch (_: Throwable) {} }
            val m = pager.javaClass.methods.firstOrNull { it.name == "setCurrentItem" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType }
            if (m != null) {
                m.isAccessible = true
                m.invoke(pager, cur)
                Logger.d("adpGet forceRebind cur=$cur ok")
            }
        } catch (_: Throwable) {}
    }

    // �?vm 窗口取同位置富数�?qp（显示源实例，带完整 user/caption�?
    private fun findWindowQp(pos: Int): Any? {
        try {
            val vm = vmRef ?: return null
            val i = Reflect.readAny(vm, "i") as? List<*> ?: return null
            if (i.isEmpty()) return null
            val idx = if (pos < 0) 0 else pos % i.size
            val el = i.getOrNull(idx) ?: return null
            val q = findQpInObject(el)
            if (q != null) return q
            return el
        } catch (_: Throwable) { return null }
    }

    // 干净视频写进 vm 窗口同槽：当前图读取会直接拿到干净�?
    private fun applyWindowClean(pos: Int) {
        try {
            val vm = vmRef ?: return
            @Suppress("UNCHECKED_CAST")
            val list = Reflect.readAny(vm, "i") as? MutableList<Any?> ?: return
            if (list.isEmpty()) return
            val idx = if (pos < 0) 0 else pos % list.size
            val clean = pickFromQueue() ?: return
            list[idx] = clean
            Logger.d("adpGet windowClean size=${list.size} idx=$idx pos=$pos ok")
        } catch (_: Throwable) {}
    }

    // holder 图里�?Fragment 实例
    private fun findFragInHolder(holder: Any?): Any? {
        if (holder == null) return null
        try {
            var c: Class<*>? = holder.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    val ft = f.type.name
                    if (ft.contains("Fragment") && !ft.contains("FragmentManager") && !ft.contains("FragmentTransaction")) {
                        try { f.isAccessible = true; f.get(holder)?.let { return it } } catch (_: Throwable) {}
                    }
                }
                c = c.superclass; lvl++
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun findDataSource(frag: Any) {
        Logger.safe("findDataSource") {
            val qc = qpClassRef
            var vm: Any? = null
            var c: Class<*>? = frag.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(frag) ?: continue
                        if (v.javaClass.name.endsWith("SlidePlayViewModel")) {
                            Logger.d("NASA vm=${v.javaClass.name}")
                            vm = v
                            val ds = Reflect.callMethod(v, "getDataSource")
                            if (ds != null) {
                                Logger.d("NASA dataSource=${ds.javaClass.name}")
                                hookDataSource(ds.javaClass)
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            // 混淆兜底：按方法签名�?(int)->QPhoto �?ViewModel
            if (vm == null && qc != null) {
                c = frag.javaClass; lvl = 0
                outer@ while (c != null && c != Any::class.java && lvl < 4) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(frag) ?: continue
                            val vn = v.javaClass.name
                            if (vn.startsWith("android.") || vn.startsWith("java.") || vn.contains("Fragment")) continue
                            var n = 0
                            var mc: Class<*>? = v.javaClass
                            var ml = 0
                            while (mc != null && ml < 4) {
                                for (m in mc!!.declaredMethods) {
                                    if (m.returnType == qc && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) n++
                                }
                                mc = mc.superclass; ml++
                            }
                            if (n >= 2) {
                                vm = v
                                Logger.d("NASA vm fallback=${vn} sig=$n")
                                break@outer
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
            }
            if (vm != null) {
                hookViewModel(vm!!)
                val ds = Reflect.callMethod(vm!!, "getDataSource")
                if (ds != null) {
                    Logger.d("NASA dataSource=${ds.javaClass.name}")
                    hookDataSource(ds.javaClass)
                }
            }
        }
    }

    private var fragDiagCount = 0
    private var fragMethodsDiag = false
    private var fragFieldsDiag = false

    private fun diagFragment(frag: Any) {
        Logger.safe("diagFrag") {
            val qpClass = qpClassRef ?: return@safe
            val vm = vmRef
            // 字段级排查：Fragment 持有视频数据的字段（类型�?Photo/QPhoto 或值含文案�?
            if (!fragFieldsDiag) {
                fragFieldsDiag = true
                var mc: Class<*>? = frag.javaClass
                var mlvl = 0
                var printed = 0
                while (mc != null && mc != Any::class.java && mlvl < 5) {
                    for (f in mc!!.declaredFields) {
                        val ft = f.type.name
                        if (ft.contains("Photo", true) || ft.contains("QPhoto", true) || ft.contains("Feed", true)) {
                            try {
                                f.isAccessible = true
                                val v = f.get(frag)
                                if (v != null && printed < 8) {
                                    val vcap = try { readCaption(v) } catch (_: Throwable) { null }
                                    Logger.d("fragF ${f.name} type=$ft cap=${vcap?.take(16) ?: "?"}")
                                    printed++
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    mc = mc.superclass; mlvl++
                }
            }
            if (!fragMethodsDiag) {
                fragMethodsDiag = true
                var mc: Class<*>? = frag.javaClass
                var mlvl = 0
                while (mc != null && mc != Any::class.java && mlvl < 5) {
                    for (m in mc!!.declaredMethods) {
                        if (m.parameterTypes.isEmpty() && m.returnType == Void.TYPE) {
                            Logger.d("frag void: ${m.name}")
                        }
                    }
                    mc = mc.superclass; mlvl++
                }
            }
            if (!feedPagerFound) {
                try {
                    val act = tracked
                    val decor = act?.window?.decorView as? ViewGroup
                    if (decor != null) {
                        findPager(decor)
                    }
                } catch (_: Throwable) {}
            }
            var c: Class<*>? = frag.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 5) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(frag) ?: continue
                        if (qpClass.isAssignableFrom(v.javaClass)) {
                            val hit = shouldFilterFeed(v)
                            val cap = readCaption(v)
                            fragDiagCount++
                            if (fragDiagCount <= 5 || fragDiagCount % 100 == 0) {
                                Logger.d("frag M #$fragDiagCount: hit=$hit cap=${cap?.take(25)}")
                            }
                            if (hit && vm != null) {
                                var clean: Any? = null
                                for (i in 0 until 10) {
                                    val qp = try { Reflect.callMethod(vm, "T0", i) } catch (_: Throwable) { null }
                                    if (qp != null && qpClass.isAssignableFrom(qp.javaClass) && !shouldFilterFeed(qp)) {
                                        clean = qp; break
                                    }
                                }
                                if (clean != null) {
                                    f.set(frag, clean)
                                    try {
                                        val sm = vm.javaClass.getDeclaredMethod("J1", qpClass, Boolean::class.javaPrimitiveType)
                                        sm.isAccessible = true
                                        sm.invoke(vm, clean, true)
                                    } catch (_: Throwable) {}
                                    Logger.d("frag M replaced: ${cap?.take(20)} -> ${readCaption(clean)?.take(20)}")
                                }
                            }

                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
    }


    private val hookedAdpClasses = mutableSetOf<String>()
    private var feedPagerFound = false
    private var feedPagerLogCount = 0
    private var adpDumpCount = 0
    private fun findPager(v: View) {
        val n = v.javaClass.name
        val isPager = n.contains("CustomAnimationViewPager") || n.contains("ScrollStrategyViewPager") ||
            n.contains("VerticalViewPager") || n.contains("LiveSlideViewPager") || n.contains("LiveSafeViewPager") ||
            (v.id != View.NO_ID && try { v.resources.getResourceEntryName(v.id) == "nasa_groot_view_pager" } catch (_: Throwable) { false }) ||
            (v.id != View.NO_ID && try { v.resources.getResourceEntryName(v.id) == "milano_container_layout" } catch (_: Throwable) { false })
        if (isPager) {
            if (pagerCache == null) pagerCache = v
            try { hookPagerClass(v.javaClass) } catch (_: Throwable) {}
            val adp = try { Reflect.callMethod(v, "getAdapter") } catch (_: Throwable) { null }
            if (adp == null) {
                if (feedPagerFound) return
                Logger.safe("feedPagerNoAdp") { Logger.d("feedPager found but no adapter yet: ${v.javaClass.name}") }
                return
            }
            val isFirst = !feedPagerFound
            feedPagerFound = true
            if (isFirst) {
                adpRef = adp
                pagerCache = v
            }
            if (feedPagerLogCount < 20) {
                feedPagerLogCount++
                Logger.safe("feedPagerLog") { Logger.d("feedPager: ${v.javaClass.name} adp=${adp.javaClass.name} first=$isFirst") }
            }
            try { if (adpRefs.none { it === adp }) adpRefs.add(adp) } catch (_: Throwable) {}
            if (adp.javaClass.name.startsWith("l3c")) Logger.d("feedPager DETAIL-adp: ${adp.javaClass.name} pager=${v.javaClass.simpleName}")
            // ★ 从 adapter 反向找 VM（fragSeq aq 未调用时的替代路径）：
            // 扫 adapter 字段找 SlidePlayViewModel，设 vmRef + hookViewModel + filterVmLists
            if (vmRef == null) {
                if (adpDumpCount < 3) { adpDumpCount++; val sb = StringBuilder("ADPDUMP ${adp.javaClass.name}:"); var dc: Class<*>? = adp.javaClass; var dl = 0; while (dc != null && dc != Any::class.java && dl < 4) { for (df in dc!!.declaredFields) { if (java.lang.reflect.Modifier.isStatic(df.modifiers)) continue; try { df.isAccessible = true; val dv = df.get(adp); sb.append(" ${df.name}=${dv?.javaClass?.simpleName ?: "null"}") } catch (_: Throwable) {} }; dc = dc.superclass; dl++ }; Logger.d(sb.toString()) }
                var c2: Class<*>? = adp.javaClass
                var lvl2 = 0
                while (c2 != null && c2 != Any::class.java && lvl2 < 4) {
                    for (f2 in c2!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f2.modifiers)) continue
                        try {
                            f2.isAccessible = true
                            val fv = f2.get(adp) ?: continue
                            if (fv.javaClass.name.contains("SlidePlay") || fv.javaClass.name.contains("ViewModel")) {
                                vmRef = fv
                                Logger.always("vmFromAdp: ${fv.javaClass.name} via ${f2.name}")
                                try { hookViewModel(fv) } catch (_: Throwable) {}
                                try { filterVmLists(fv) } catch (_: Throwable) {}
                                break
                            }
                        } catch (_: Throwable) {}
                    }
                    if (vmRef != null) break
                    c2 = c2.superclass; lvl2++
                }
            }

            // ★ 持续清洗：vmFromAdp 首次设 vmRef 后 filterVmLists 只调了一次（此时 i 可能空）。
            // 后续 feed 数据加载后 i 被填充，但 fragSeq aq 不调用 → filterVmLists 不再触发。
            // findPager 每 ~3s 由 check() 触发，此处补调 filterVmLists（500ms 节流自防过度）
        if (vmRef != null) { try { filterVmLists(vmRef!!) } catch (_: Throwable) {} }
        // LAFIND：脏元素身份反查真源字段（诊断用）
        if (!Logger.quiet) try { laFind() } catch (_: Throwable) {}

            try { hookPagerAdapter(adp.javaClass) } catch (t: Throwable) { Logger.always("hookPagerAdapter exc: ${t.message}") }
            if (isFirst) {
                for (provName in listOf("G", "H", "getProvider")) {
                    val provider = try { Reflect.callMethod(adp, provName) } catch (_: Throwable) { null }
                    if (provider != null) {
                        Logger.safe("feedPagerProv") { Logger.d("feedPager provider via $provName: ${provider.javaClass.name}") }
                        hookDataProvider(provider.javaClass)
                        break
                    }
                }
            }
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { findPager(it) }
            }
        }
    }

    private val hookedPagerCls = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    // ScrollStrategyViewPager 等横滑 pager：hook 其基类(含 androidx ViewPager)的 instantiateItem/getItem/adapter 相关
    private fun hookPagerClass(startCls: Class<*>) {
        try {
            val xp = xpRef ?: return
            var cc: Class<*>? = startCls
            var lvl = 0
            while (cc != null && cc != Any::class.java && lvl < 6) {
                val clsNow = cc
                if (!hookedPagerCls.add(clsNow.name)) { cc = cc.superclass; lvl++; continue }
                for (m in clsNow.declaredMethods) {
                    val nm = m.name
                    if (!m.returnType.isPrimitive && m.returnType != Void.TYPE &&
                        (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType ||
                         m.parameterTypes.size == 2 && m.parameterTypes[1] == Int::class.javaPrimitiveType)) {
                        Logger.safe("hookPager.${clsNow.simpleName}.${nm}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("pager.${clsNow.name}.${nm}").intercept { chain ->
                                val result = chain.proceed()
                                try {
                                    val pos = chain.args.lastOrNull() as? Int ?: -1
                                    if (result != null) {
                                        val qp = findQpInObject(result)
                                        val hit = if (qp != null) shouldFilterFeed(qp) else false
                                        if (pagerDiag < 25) {
                                            pagerDiag++
                                            Logger.d("pager i ${nm}(#$pos) -> ${result.javaClass.simpleName} hit=$hit qp=${qp != null}")
                                        }
                                        if (qp != null && hit) {
                                            val clean = pickFromQueue()
                                            if (clean != null) {
                                                val sw = writeQpInto(result, clean)
                                                pagerSwapCount++
                                                Logger.d("pager swap ${nm}(#$pos) sw=$sw ${readCaption(qp)?.take(15)}")
                                            }
                                        } else if (qp != null && !hit && liveWindowDiag < 8) {
                                            // ★ 视频卡是否带"直播浮窗/进入直播间引导"：找 QP 树里的 live 状态字段
                                            val liveInfo = findLiveWindowField(qp)
                                            if (liveInfo != null) {
                                                liveWindowDiag++
                                                Logger.d("LIVEWIN ${nm}(#$pos) $liveInfo cap=${readCaption(qp)?.take(16)}")
                                            }
                                        }
                                    }
                                } catch (_: Throwable) {}
                                result
                            }
                        }
                    }
                }
                cc = cc.superclass; lvl++
            }
        } catch (_: Throwable) {}
    }
    private var liveWindowDiag = 0
    private fun findLiveWindowField(root: Any?): String? {
        if (root == null) return null
        try {
            val visited = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Any, Boolean>())
            val queue = ArrayDeque<Pair<Any, Int>>()
            queue.addLast(root to 0)
            visited.add(root)
            while (queue.isNotEmpty()) {
                val (obj, depth) = queue.removeFirst()
                if (depth > 4) continue
                var c: Class<*>? = obj.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        try {
                            f.isAccessible = true
                            val v = f.get(obj)
                            if (v == null) continue
                            val fn = f.name.lowercase()
                            val cn = v.javaClass.name
                            val liveHit = cn.contains("Live") && (cn.contains("Info") || cn.contains("Status") || cn.contains("Play") || cn.contains("Feed") || cn.contains("Window") || cn.contains("Guide") || cn.contains("Preview"))
                            if (liveHit || fn.contains("livestatus") || fn.contains("isliving") || fn.contains("living") && (fn.contains("user") || fn.contains("author"))) {
                                return "[${c.simpleName}]${f.name}:${v.javaClass.simpleName}"
                            }
                        } catch (_: Throwable) {}
                    }
                    c = c.superclass; lvl++
                }
                if (depth < 3 && !obj.javaClass.name.startsWith("java.")) {
                    var c2: Class<*>? = obj.javaClass
                    var l2 = 0
                    while (c2 != null && c2 != Any::class.java && l2 < 2) {
                        for (f in c2!!.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            try {
                                f.isAccessible = true
                                val v = f.get(obj) ?: continue
                                if (!v.javaClass.isPrimitive && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("[") && visited.add(v)) {
                                    queue.addLast(v to depth + 1)
                                }
                            } catch (_: Throwable) {}
                        }
                        c2 = c2.superclass; l2++
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }
    private var pagerDiag = 0
    private var pagerSwapCount = 0

    private val hookedProvClasses = mutableSetOf<String>()
    private var provDiag = false
    private fun hookDataProvider(c: Class<*>) {
        val xp = xpRef ?: return
        synchronized(hookedProvClasses) { if (!hookedProvClasses.add(c.name)) return }
        Logger.d("hookDataProvider: ${c.name}")
        for (m in c.declaredMethods) {
            Logger.d("  prov m: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
            val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
            if (isRetList) {
                Logger.safe("hookProvList.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("provL.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (!provDiag && result is MutableList<*> && result.isNotEmpty()) {
                                provDiag = true
                                val elem = result[0]
                                Logger.d("provList diag: size=${result.size} elemCls=${elem?.javaClass?.name}")
                                val qp = elem?.let { findQpInObject(it) }
                                Logger.d("provList qp: ${qp != null}")
                            }
                            if (result is MutableList<*>) {
                                val hits = result.filter { it != null && (try { shouldFilterFeed(it) } catch (_: Throwable) { false }) }
                                if (hits.isEmpty()) {
                                    val wrapHits = result.filter { it != null && findQpInObject(it)?.let { qp -> shouldFilterFeed(qp) } == true }
                                    if (wrapHits.isNotEmpty()) {
                                        @Suppress("UNCHECKED_CAST")
                                        (result as MutableList<Any?>).removeAll(wrapHits)
                                        Logger.d("provWrap filtered ${wrapHits.size} via ${m.name}")
                                    }
                                } else {
                                    @Suppress("UNCHECKED_CAST")
                                    (result as MutableList<Any?>).removeAll(hits)
                                    Logger.d("prov filtered ${hits.size} via ${m.name}")
                                }
                            }
                        } catch (_: Throwable) {}
                        result
                    }
                }
            }
            if (m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == Int::class.javaPrimitiveType && !m.returnType.isPrimitive && m.returnType != Void.TYPE) {
                Logger.safe("hookProvGet.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("provG.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (result != null) {
                                val qp = findQpInObject(result)
                                if (qp != null && shouldFilterFeed(qp)) {
                                    Logger.d("provGet blocked pos=${chain.args[0]} ${readCaption(qp)?.take(25)}")
                                }
                            }
                        } catch (_: Throwable) {}
                        result
                    }
                }
            }
        }
    }

    private val adpCallCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var adpGetDiag = 0
    private var adpGetLiveSkipDiag = 0
    private var adpProvDiag = 0
    private var adpQpDiag = 0
    private var adpDDump = 0
    private var adpXDump = 0
    private var adpXLiveZapDiag = 0
    private var adpXRedirectDiag = 0

    private val adpXDumped = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private fun hookPagerAdapter(c: Class<*>) {
        val xp = xpRef ?: run { Logger.always("hookPagerAdapter skip: xpRef null"); return }
        val added = synchronized(hookedAdpClasses) { hookedAdpClasses.add(c.name) }
        if (!added) { Logger.d("hookPagerAdapter dup: ${c.name}"); return }
        Logger.d("hookPagerAdapter: ${c.name}")
        // 诊断：dump 类层次全部方法，找数据供给方法
        var dcls: Class<*>? = c
        var dlvl = 0
        while (dcls != null && dcls != Any::class.java && dlvl < 5) {
            val dcn = dcls!!
            for (m in dcn.declaredMethods) {
                val pDesc = m.parameterTypes.joinToString(",") { it.simpleName }
                if (m.parameterTypes.size <= 3) {
                    Logger.d("  adpM[${dcn.simpleName}]: ${m.name}($pDesc) -> ${m.returnType.simpleName}")
                }
            }
            dcls = dcn.superclass; dlvl++
        }
        // 决定性：hook D(int)/p(ViewGroup,int) dump 返回结构 + 所有 List 写入方法源头过滤
        var qcls: Class<*>? = c
        var qlvl = 0
        while (qcls != null && qcls != Any::class.java && qlvl < 5) {
            val qcn = qcls!!
            for (m in qcn.declaredMethods) {
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") }
                val isDMethod = m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.name.length <= 2
                val isInst = m.parameterTypes.size == 2 && m.parameterTypes[0].name.contains("ViewGroup") &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType && !m.returnType.isPrimitive && m.returnType != Void.TYPE
                if (hasListParam || isDMethod || isInst) {
                    Logger.d("  hookAdpX[${qcn.simpleName}]: ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}) -> ${m.returnType.simpleName}")
                    Logger.safe("hookAdpX.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpX.${qcn.name}.${m.name}").intercept { chain ->
                            try { filterListArgs(chain.args) } catch (_: Throwable) {}
                            if (m.name == "p" && chain.args.size >= 2) {
                                try {
                                    val pos = chain.args[1] as Int
                                    val adp = chain.thisObject
                                    val data = try { Reflect.callMethod(adp, "g0", pos) as? List<*> } catch (_: Throwable) { null }
                                    if (data != null && data.any { it != null && shouldFilterFeed(it) }) {
                                        for (delta in listOf(1, -1, 2, -2, 3, -3, 4, -4)) {
                                            val np = pos + delta
                                            val nd = try { Reflect.callMethod(adp, "g0", np) as? List<*> } catch (_: Throwable) { null }
                                            if (nd != null && nd.isNotEmpty() && !nd.any { it != null && shouldFilterFeed(it) }) {
                                                chain.args[1] = np
                                                if (adpXRedirectDiag < 30) { adpXRedirectDiag++; Logger.d("adpX p REDIRECT #$pos -> #$np") }
                                                try { triggerRefresh() } catch (_: Throwable) {}
                                                break
                                            }
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }
                            val r = chain.proceed()

                            try {
                                val pos = chain.args.lastOrNull() as? Int ?: -1
                                val isPD = m.name == "p" || m.name == "D"
                                val shouldDump = if (isPD) adpXDump < 40 && adpXDumped.add("pd_" + pos) else adpXDumped.add(qcn.name + "." + m.name) && adpXDump < 25
                                if (shouldDump) {
                                    adpXDump++
                                    Logger.d("adpX ${m.name} #$pos ret=${r?.javaClass?.name ?: "null"}")
                                    if (r != null && isPD) {
                                        var rc: Class<*>? = r.javaClass
                                        var rl = 0
                                        while (rc != null && rc != Any::class.java && rl < 3) {
                                            for (rf in rc!!.declaredFields) {
                                                if (java.lang.reflect.Modifier.isStatic(rf.modifiers)) continue
                                                try { rf.isAccessible = true; val rv = rf.get(r); Logger.d("  adpXfld ${rf.name}:${rf.type.simpleName}=${rv?.javaClass?.name ?: "null"}") } catch (_: Throwable) {}
                                            }
                                            rc = rc.superclass; rl++
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}

                            r
                        }
                    }
                }
            }
            qcls = qcn.superclass; qlvl++
        }
        var cls: Class<*>? = c
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 4) {
            for (m in cls!!.declaredMethods) {
                if (m.returnType.name.contains("Fragment") && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == Any::class.java) {
                    Logger.d("  hook adp create: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) in ${cls.name}")
                Logger.safe("hookAdpF.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adp.F.${c.name}").intercept { chain ->
                        try {
                            val a0 = chain.args[0]
                            if (a0 != null && shouldFilterFeed(a0)) {
                                Logger.d("adp F blocked: ${readCaption(a0)?.take(25)}")
                                val vm = vmRef
                                if (vm != null) {
                                    var replaced = false
                                    for (i in 0 until 15) {
                                        val qp = try { Reflect.callMethod(vm, "T0", i) } catch (_: Throwable) { null }
                                        if (qp != null && !shouldFilterFeed(qp)) {
                                            chain.args[0] = qp
                                            replaced = true
                                            Logger.d("adp F replaced -> ${readCaption(qp)?.take(25)}")
                                            break
                                        }
                                    }
                                    if (!replaced) {
                                        for (i in 0 until 15) {
                                            val qp = try { Reflect.callMethod(vm, "U0", i) } catch (_: Throwable) { null }
                                            if (qp != null && !shouldFilterFeed(qp)) {
                                                chain.args[0] = qp
                                                Logger.d("adp F replaced U0 -> ${readCaption(qp)?.take(25)}")
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                    }
                }
            }
                // adapter 的 set/add/addAll(List) 方法：直播从这塞进信息流，在参数阶段就剔掉
                val hasListParam = m.parameterTypes.any { it == java.util.List::class.java || it.name.contains("List") || it.name.contains("Collection") }
                if (hasListParam && m.declaringClass == cls) {
                    Logger.safe("hookAdpList.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpList.${c.name}.${m.name}").intercept { chain ->
                            try { filterListArgs(chain.args) } catch (_: Throwable) {}
                            chain.proceed()
                        }
                    }
                }
                if (m.parameterTypes.size <= 3 && m.declaringClass == cls) {
                    val p1 = m.parameterTypes.firstOrNull()
                    val isIntP = p1 == Int::class.javaPrimitiveType
                    val nonPrimRet = !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.returnType != java.lang.String::class.java
                    val retFrag = m.returnType.name.contains("Fragment")
                    val retList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
                    // 供给方法：单 int 参返回对象（getItem/D）；(ViewGroup,int) 返回 View/Fragment（instantiateItem）；
                    // 或返回 Fragment/List 的任意短参方法
                    val isSupply = (m.parameterTypes.size == 1 && isIntP && nonPrimRet) ||
                        (m.parameterTypes.size == 2 && m.parameterTypes[0].name.contains("ViewGroup") && m.parameterTypes[1] == Int::class.javaPrimitiveType && (retFrag || nonPrimRet)) ||
                        (retFrag && m.parameterTypes.size <= 2) ||
                        (retList && m.parameterTypes.size <= 2 && isIntP)
                    if (isSupply) {
                        Logger.safe("hookAdpGet.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpGet.${c.name}.${m.name}").intercept { chain ->
                                if (adpRef == null) adpRef = chain.thisObject
                                val result = chain.proceed()
                                try {
                                    if (result != null && !adpGetSwapIn) {
                                        val qp = findQpInObject(result)
                                        if (qp != null) captureFeedItem(qp)
                                        val pos = (chain.args.getOrNull(0) as? Int) ?: -1
                                        var clsQp = qp
                                        // 空壳实例兜底：用同位�?vm 窗口的富 qp 分类（显示源=qm 有完整数据）
                                        if (clsQp == null || readUserName(clsQp, Reflect.readAny(clsQp, "mEntity") ?: clsQp).isEmpty()) {
                                            clsQp = findWindowQp(pos) ?: clsQp
                                        }
                                        // ★ QPhoto 提不到时用 holder 的 Fragment 类型判定（g3c.a 的 b 字段即页面 Fragment）：
                                        // 直播 holder 的 Fragment 类名含 Live，照样进脏分支换页
                                        val holderLive = findFragInHolder(result)?.javaClass?.name?.let { fn -> fn.contains("Live") || fn.contains("Ad") } == true
                                        // ★★ 再 BFS 全图找任何 Live/Ad 实体（直播卡可能渲染在 NasaPhotoDetailFragment 里，
                                        // Fragment 类名不含 Live，QPhoto 也提不到，只能全图找实体类名）
                                        val holderDirtyEnt = if (!holderLive) findDirtyEntityInHolder(result) else null
                                        if ((clsQp != null && shouldFilterFeed(clsQp)) || holderLive || holderDirtyEnt != null) {
                                            if (holderDirtyEnt != null) Logger.d("adpGet holderLiveEnt: ${holderDirtyEnt.javaClass.name}")
                                            adpGetSwapIn = true
                                            // 任何脏 holder（直播/广告/剧集）：双向扫干净 holder 直接返回，
                                            // 让脏页根本不显示（滑过去是相邻普通视频），不改写数据(避免 ClassCastException)。
                                            try {
                                                val mInv = m; mInv.isAccessible = true
                                                fun probeClean(p: Int): Any? {
                                                    val probe = try { mInv.invoke(chain.thisObject, p) } catch (_: Throwable) { null } ?: return null
                                                    val pq = findQpInObject(probe)
                                                    if (pq == null || shouldFilterFeed(pq)) return null
                                                    val pfrag = Reflect.readAny(probe, "b") ?: findFragInHolder(probe)
                                                    val pfn = pfrag?.javaClass?.name ?: ""
                                                    if (pfn.contains("Live") || pfn.contains("Ad")) return null
                                                    return probe
                                                }
                                                for (k in 1..300) {
                                                    probeClean(pos - k)?.let { if (adpGetLiveSkipDiag < 40) { adpGetLiveSkipDiag++; Logger.d("adpGet skip #$pos -> #${pos - k} k=-$k") }; return@intercept it }
                                                    probeClean(pos + k)?.let { if (adpGetLiveSkipDiag < 40) { adpGetLiveSkipDiag++; Logger.d("adpGet skip #$pos -> #${pos + k} k=$k") }; return@intercept it }
                                                }
                                            } catch (_: Throwable) {}
                                            if (adpGetLiveSkipDiag < 40) { adpGetLiveSkipDiag++; Logger.d("adpGet skip #$pos noClean") }
                                            return@intercept result
                                            if (clsQp != null) {
                                                // 记录脏播放地址 + 挂视频模�?URL getter（画面播放源拦截�?
                                                val dirtyEnt = Reflect.readAny(clsQp, "mEntity") ?: clsQp
                                                recordDirtyUrl(dirtyEnt)
                                                // ①干净视频写进 vm 窗口同槽，当前图读取直接拿到干净�?
                                                applyWindowClean(pos)
                                                // ★★★ 窗口已写入干净视频：立即重取当前 pos 的 holder。
                                                // holder 是在写入前构建的（g3c.a 持有已实例化 Fragment），只有重取才能让
                                                // 页面从干净窗口重新构建。milanoSkip 前扫 pos+k 超出窗口全返回 null，
                                                // 这一招直接命中「窗口已干净但 holder 还脏」的死结。
                                                try {
                                                    val mInv = m
                                                    mInv.isAccessible = true
                                                    val fresh = try { mInv.invoke(chain.thisObject, pos) } catch (_: Throwable) { null }
                                                    if (fresh != null && fresh !== result) {
                                                        Logger.d("adpGet freshClean #$pos (${readCaption(clsQp)?.take(10)})")
                                                        return@intercept fresh
                                                    }
                                                    if (fresh != null) Logger.d("adpGet freshSame #$pos (adapter缓存同一实例)")
                                                } catch (_: Throwable) {}
                                            }
                                            // ②dump holder 真身（找 fragment/photo 绑定字段�?
                                            if (holderDumpCount < 5) { holderDumpCount++; dumpHolderGraph(result, 0) }
                                            // ③枚�?holder �?Fragment 的空参方法（找重绑方法名�?
                                            val holderFrag = findFragInHolder(result)
                                            if (holderFrag != null) diagFragment(holderFrag)
                                            // ③当前页命中：原�?setCurrentItem(同位�? 强制重实例化显示干净�?
                                            forceRebindCurrent(pos)
                                            // ★ 先无条件向前扫干净 holder 换页：writeQpInto 只改 QPhoto 数据，
                                            // 改不了已实例化的 LivePreviewFragment，普通 feed(1/3/5)直播必须直接换页
                                            try {
                                                val mInv = m
                                                mInv.isAccessible = true
                                                for (k in 1..200) {
                                                    val probeHolder = try { mInv.invoke(chain.thisObject, pos + k) } catch (_: Throwable) { null }
                                                    if (probeHolder == null) continue
                                                    var probeDirty = false
                                                    val probeQp = findQpInObject(probeHolder)
                                                    if (probeQp != null && shouldFilterFeed(probeQp)) probeDirty = true
                                                    if (!probeDirty) {
                                                        val frag = Reflect.readAny(probeHolder, "b") ?: findFragInHolder(probeHolder)
                                                        if (frag != null) {
                                                            val fn = frag.javaClass.name
                                                            if (fn.contains("Live") || fn.contains("Ad")) probeDirty = true
                                                        }
                                                    }
                                                    if (!probeDirty) {
                                                        Logger.d("adpGet skip #$pos -> #${pos + k} k=$k (${readCaption(qp)?.take(10)})")
                                                        return@intercept probeHolder
                                                    }
                                                }
                                            } catch (t: Throwable) { Logger.d("skipScan err: ${t.javaClass.simpleName}: ${t.message}") }
                                            try {
                                                val cleanPos = findCleanPos(pos)
                                                if (cleanPos >= 0 && cleanPos != pos) {
                                                    Logger.d("adpGet swap #$pos -> #$cleanPos (${readCaption(qp)?.take(15)})")
                                                    val cleanHolder = try {
                                                        val dm = chain.thisObject.javaClass.getDeclaredMethod(m.name, Int::class.javaPrimitiveType)
                                                        dm.isAccessible = true
                                                        dm.invoke(chain.thisObject, cleanPos)
                                                    } catch (_: Throwable) { null }
                                                    if (cleanHolder != null) return@intercept cleanHolder
                                                } else {
                                                    // 兜底：把 holder 的照片字段直接换�?T0/U0 源里的干净 QPhoto（vm ret 源是普通视频）
                                                    val cleanQp = findCleanQp()
                                                    if (cleanQp != null) {
                                                        val sw = writeQpInto(result, cleanQp)
                                                        Logger.d("adpGet holderReswap #$pos ${if (sw > 0) "ok($sw)" else "fail"} (${readCaption(qp)?.take(15)})")
                                                        if (sw > 0) return@intercept result
                                                        Logger.d("adpGet postFail pos=$pos sw=$sw cleanQpCls=${cleanQp.javaClass.simpleName}")
                                                        // Milano �?�?00000)：holder �?Fragment 容器（直�?LivePreviewFragment），QPhoto 替换无效�?
                                                        // �?adapter 真实 classloader �?hook PageList 数据源，并向前扫干净 holder 直接换页
                                                        if (pos >= 500000) {
                                                            val adpCl = chain.thisObject.javaClass.classLoader
                                                            if (xpRef != null && adpCl != null) {
                                                                try { hookPageLists(xpRef!!, adpCl) } catch (_: Throwable) {}
                                                            }
                                                            // 向前扫最�?200 个位置，返回第一个干净 holder（跳直播/广告页）�?
                                                            // 直接反射调用原方法（不触发本 hook 重入，与 cleanPos 分支同法�?
                                                            try {
                                                                val mInv = m
                                                                mInv.isAccessible = true
                                                                for (k in 1..200) {
                                                                    val probeHolder = try { mInv.invoke(chain.thisObject, pos + k) } catch (_: Throwable) { null }
                                                                    if (probeHolder == null) continue
                                                                    // 反转判定：只有「确认脏」（QPhoto 命中 / Fragment 是 Live·Ad）才跳过；
                                                                    // 空壳或未实例化无法确认脏 = 视为干净直接返回，避免误判导致直播页刷不出来
                                                                    var probeDirty = false
                                                                    val probeQp = findQpInObject(probeHolder)
                                                                    if (probeQp != null && shouldFilterFeed(probeQp)) probeDirty = true
                                                                    if (!probeDirty) {
                                                                        val frag = Reflect.readAny(probeHolder, "b") ?: findFragInHolder(probeHolder)
                                                                        if (frag != null) {
                                                                            val fn = frag.javaClass.name
                                                                            if (fn.contains("Live") || fn.contains("Ad")) probeDirty = true
                                                                        }
                                                                    }
                                                                    if (!probeDirty) {
                                                                        Logger.d("adpGet milanoSkip #$pos -> #${pos + k} k=$k")
                                                                        return@intercept probeHolder
                                                                    }
                                                                    }
                                                                Logger.d("adpGet milano noCleanHolder pos=$pos")
                                                    } catch (t: Throwable) { Logger.d("milanoScan err: ${t.javaClass.simpleName}: ${t.message}") }

                                                        }
                                                    } else {
                                                        Logger.d("adpGet noClean #$pos hit (${readCaption(qp)?.take(15)})")
                                                    }
                                                }
                                            } finally { adpGetSwapIn = false }
                                        }
                                        // 干净项入池：D 每取一个位置，普通视频就是池子的食粮
                                        if (qp != null && !shouldFilterFeed(qp)) {
                                            try { offerClean(qp) } catch (_: Throwable) {}
                                        }
                                        // ===== 原诊断（节流�?=====
                                        if (adpGetDiag < 10) {
                                            adpGetDiag++
                                            qp?.let { q0 ->
                                                Logger.d("adpGet ${m.name}(#${chain.args[0]}) ret=${result.javaClass.name} qp hit=${shouldFilterFeed(q0)} cap=${readCaption(q0)?.take(20)}")
                                            }
                                            if (!adpSelfDumped) {
                                                adpSelfDumped = true
                                                dumpAdapterSelf(chain.thisObject)
                                            }
                                        }
                                        // ★★★ adapter 自持列表每次 D() 都修（去掉一次门控）：o 列表是实际显示源，
                                        // rerank 每次换页都会往 o 里塞新的直播项，必须持续清理。
                                        try { fixAdapterSelfAlways(chain.thisObject) } catch (_: Throwable) {}
                                    }
                                } catch (_: Throwable) {}
                                result
                            }
                        }
                    } else if (m.parameterTypes.isEmpty() && nonPrimRet) {
                        Logger.safe("hookAdpProv.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpProv.${c.name}.${m.name}").intercept { chain ->
                                val result = chain.proceed()
                                try {
                                    if (result != null && adpProvDiag < 10) {
                                        adpProvDiag++
                                        Logger.d("adpProv ${m.name}() ret=${result.javaClass.name}")
                                        if (result.javaClass.name != "com.yxcorp.gifshow.entity.QPhoto") {
                                            dumpProvider(result)
                                        }
                                    }
                                } catch (_: Throwable) {}
                                result
                            }
                        }
                    } else if (m.parameterTypes.size >= 1 && m.parameterTypes.any { it.name.contains("QPhoto") }) {
                        Logger.safe("hookAdpQp.${m.name}") {
                            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("adpQp.${c.name}.${m.name}").intercept { chain ->
                                try {
                                    val qp = chain.args.firstOrNull { it != null && qpClassRef?.isAssignableFrom(it.javaClass) == true }
                                    if (qp != null && adpQpDiag < 15) {
                                        adpQpDiag++
                                        Logger.d("adpQp ${m.name}(${qp.javaClass.simpleName}) ret=${m.returnType.simpleName} hit=${shouldFilterFeed(qp)} cap=${readCaption(qp)?.take(20)}")
                                    }
                                } catch (_: Throwable) {}
                                chain.proceed()
                            }
                        }
                    }
                }
            }
            cls = cls.superclass; lvl++
        }
    }

    private var adpSelfDumped = false
    private var adpGetSwapIn = false
    // D(pos) 命中时找下一个干净位置的绝�?pos；找不到返回 -1（保持原样，min-1 兜底�?
    private fun findCleanPos(pos: Int): Int {
        val lists = mutableListOf<MutableList<Any?>>()
        try {
            val vm = vmRef
            if (vm != null) {
                val i = Reflect.readAny(vm, "i")
                if (i is MutableList<*> && i.isNotEmpty()) @Suppress("UNCHECKED_CAST") lists.add(i as MutableList<Any?>)
            }
        } catch (_: Throwable) {}
        try {
            val mList = adapterMainList()
            if (mList != null && lists.none { it === mList }) @Suppress("UNCHECKED_CAST") lists.add(mList as MutableList<Any?>)
        } catch (_: Throwable) {}
        for (list in lists) {
            if (list.isEmpty()) continue
            val size = list.size
            val idx = ((pos % size) + size) % size
            val qp0 = list[idx]?.let { findQpInObject(it) }
            if (qp0 == null || !shouldFilterFeed(qp0)) return pos
            for (k in 1..size) {
                val qpK = list[(idx + k) % size]?.let { findQpInObject(it) }
                if (qpK != null && !shouldFilterFeed(qpK)) return pos + k
            }
        }
        return -1
    }
    private fun adapterMainList(): MutableList<Any?>? {
        val adp = adpRef ?: return null
        return try {
            var c: Class<*>? = adp.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    if (f.name == "M" && MutableList::class.java.isAssignableFrom(f.type)) {
                        f.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        return f.get(adp) as? MutableList<Any?>
                    }
                }
                c = c.superclass; lvl++
            }
            null
        } catch (_: Throwable) { null }
    }

    private fun dumpAdapterSelf(adp: Any?) {
        if (adp == null) return
        Logger.safe("dumpAdpSelf") {
            val sb = StringBuilder()
            var c: Class<*>? = adp.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(adp)
                        val desc = if (v is List<*>) "List(size=${v.size})" else v?.javaClass?.simpleName ?: "null"
                        sb.append("[${c.simpleName}]${f.name}:${desc} ")
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            Logger.d("adpSelf ${adp.javaClass.name}: $sb")
            adpRef = adp
            var c2: Class<*>? = adp.javaClass
            var lvl2 = 0
            while (c2 != null && c2 != Any::class.java && lvl2 < 4) {
                for (f in c2!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(adp)
                        if (v is MutableList<*> && v.size > 0) {
                            val qp = v[0]?.let { findQpInObject(it) }
                            val hits = v.filter { it != null && findQpInObject(it)?.let { q -> shouldFilterFeed(q) } == true }.size
                            Logger.d("adpSelfList ${f.name} size=${v.size} elem=${v[0]?.javaClass?.name} qpFound=${qp != null} hits=$hits")
                            fixAdapterSelfAlways(adp)
                            if (f.name == "M" && !elemDumped) {
                                elemDumped = true
                                val e0 = v[0]
                                if (e0 != null) {
                                    val esb = StringBuilder()
                                    var ec: Class<*>? = e0.javaClass
                                    var elvl = 0
                                    while (ec != null && ec != Any::class.java && elvl < 3) {
                                        for (ef in ec!!.declaredFields) {
                                            if (java.lang.reflect.Modifier.isStatic(ef.modifiers)) continue
                                            try {
                                                ef.isAccessible = true
                                                val ev = ef.get(e0)
                                                val edesc = if (ev is List<*>) "List(${ev.size})" else ev?.javaClass?.simpleName ?: "null"
                                                esb.append("[${ec.simpleName}]${ef.name}:${edesc} ")
                                            } catch (_: Throwable) {}
                                        }
                                        ec = ec.superclass; elvl++
                                    }
                                    Logger.d("adpElem ${e0.javaClass.name}: $esb")
                                    val af = try { e0.javaClass.getDeclaredField("a").apply { isAccessible = true } } catch (_: Throwable) { null }
                                    val av = try { af?.get(e0) } catch (_: Throwable) { null }
                                    if (av != null) {
                                        val asb = StringBuilder()
                                        var ac: Class<*>? = av.javaClass
                                        var alvl = 0
                                        while (ac != null && ac != Any::class.java && alvl < 3) {
                                            for (af2 in ac!!.declaredFields) {
                                                if (java.lang.reflect.Modifier.isStatic(af2.modifiers)) continue
                                                try {
                                                    af2.isAccessible = true
                                                    val av2 = af2.get(av)
                                                    val adesc = if (av2 is List<*>) "List(${av2.size})" else av2?.javaClass?.simpleName ?: "null"
                                                    asb.append("[${ac.simpleName}]${af2.name}:${adesc} ")
                                                } catch (_: Throwable) {}
                                            }
                                            ac = ac.superclass; alvl++
                                        }
                                        val aqp = findQpInObject(av)
                                        Logger.d("adpElemA ${av.javaClass.name}: $asb qpIn=${aqp != null} qpHit=${aqp?.let { shouldFilterFeed(it) }}")
                                    }
                                    var mm: Class<*>? = e0.javaClass
                                    var mlvl2 = 0
                                    while (mm != null && mm != Any::class.java && mlvl2 < 3) {
                                        for (mf in mm!!.declaredMethods) {
                                            if (java.lang.reflect.Modifier.isStatic(mf.modifiers)) continue
                                            if (mf.parameterTypes.size <= 2) {
                                                Logger.d("  elem m: ${mf.name}(${mf.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${mf.returnType.simpleName}")
                                            }
                                        }
                                        mm = mm.superclass; mlvl2++
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                c2 = c2.superclass; lvl2++
            }
        }
    }

    private fun dumpProvider(obj: Any) {
        Logger.safe("dumpProv") {
            val sb = StringBuilder()
            var c: Class<*>? = obj.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        val desc = if (v is List<*>) "List(size=${v.size})" else v?.javaClass?.simpleName ?: "null"
                        sb.append("${f.name}:${desc} ")
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
            Logger.d("provDump ${obj.javaClass.name} fields: $sb")
            var c2: Class<*>? = obj.javaClass
            var lvl2 = 0
            while (c2 != null && c2 != Any::class.java && lvl2 < 3) {
                for (f in c2!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v is MutableList<*> && v.size > 0) {
                            val elem = v[0]
                            val qp = elem?.let { findQpInObject(it) }
                            Logger.d("provList ${f.name} size=${v.size} elem=${elem?.javaClass?.name} qpFound=${qp != null} qpHit=${qp?.let { shouldFilterFeed(it) }}")
                            val hits = v.filter { it != null && findQpInObject(it)?.let { q -> shouldFilterFeed(q) } == true }.size
                            Logger.d("provList ${f.name} hits=$hits/${v.size}")
                        }
                    } catch (_: Throwable) {}
                }
                c2 = c2.superclass; lvl2++
            }
        }
    }

    private fun findQpInObject(obj: Any, depth: Int = 0): Any? {
        val qpClass = qpClassRef ?: return null
        if (qpClass.isAssignableFrom(obj.javaClass)) return obj
        if (depth >= 2) return null

        if (obj is Collection<*>) {
            for (item in obj) {
                if (item != null) {
                    val r = findQpInObject(item, depth + 1)
                    if (r != null) return r
                }
            }
            return null
        }
        var c: Class<*>? = obj.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if (qpClass.isAssignableFrom(v.javaClass)) return v
                    if (depth < 1 && v.javaClass.name.contains(".") && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("android.")) {
                        val r = findQpInObject(v, depth + 1)
                        if (r != null) return r
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        return null
    }

    // ★★ holder 全图 BFS：找类名明确含 Live（直播实体/直播Fragment/直播卡容器）的脏对象。
    // 用于 QPhoto 提不到、页面 Fragment 又不是 Live 类型的场景（直播广告卡渲染在 NasaPhotoDetailFragment 里）。
    private val dirtyEntSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun findDirtyEntityInHolder(holder: Any, depth: Int = 0): Any? {
        if (depth >= 5) return null
        val name = holder.javaClass.name
        // 直接命中：类名含 live（避开 LiveStreamViewModel 等无害/含 Live 的工具类）
        if ((name.contains("Live") || name.contains("Ad")) && !name.contains("ViewModel") && !name.contains("LiveData")) {
            if (holder is Collection<*>) { /* 集合本身不判脏，看元素 */ } else if (dirtyEntSeen.add(name)) {
                Logger.d("dirtyEnt hit: $name")
                return holder
            }
        }
        if (holder is Collection<*>) {
            for (item in holder) {
                if (item != null) {
                    val r = findDirtyEntityInHolder(item, depth + 1)
                    if (r != null) return r
                }
            }
            return null
        }
        if (depth >= 4) return null
        var c: Class<*>? = holder.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(holder) ?: continue
                    if (v === holder) continue
                    val vn = v.javaClass.name
                    // 跳过 JDK/安卓容器实现 与 巨型 View 树，防爆栈
                    if (vn.startsWith("java.") || vn.startsWith("android.") || vn.startsWith("kotlin.")) {
                        if (v is Collection<*>) { val r = findDirtyEntityInHolder(v, depth + 1); if (r != null) return r }
                        continue
                    }
                    if ((vn.contains("Live") || vn.contains("Ad")) && !vn.contains("ViewModel") && !vn.contains("LiveData")) {
                        if (dirtyEntSeen.add(vn)) Logger.d("dirtyEnt hit: $vn")
                        return v
                    }
                    val r = findDirtyEntityInHolder(v, depth + 1)
                    if (r != null) return r
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        return null
    }

    private fun jumpNext(vm: Any) {
        val now = System.currentTimeMillis()
        if (now - lastJumpTime < 800) return
        lastJumpTime = now
        val qpClass = qpClassRef ?: return
        var nextQp: Any? = null
        for (i in 0 until 30) {
            val qp = try { Reflect.callMethod(vm, "T0", i) } catch (_: Throwable) { null } ?: continue
            if (qp != null && qp.javaClass == qpClass) {
                val hit = shouldFilterFeed(qp)
                Logger.d("jumpNext T0($i) hit=$hit cap=${readCaption(qp)?.take(20)}")
                if (!hit) { nextQp = qp; break }
            }
        }
        if (nextQp == null) {
            for (i in 0 until 30) {
                val qp = try { Reflect.callMethod(vm, "U0", i) } catch (_: Throwable) { null } ?: continue
                if (qp != null && qp.javaClass == qpClass) {
                    val hit = shouldFilterFeed(qp)
                    if (!hit) { nextQp = qp; break }
                }
            }
        }
        if (nextQp == null) { Logger.d("jumpNext no clean video"); return }
        try {
            val m = vm.javaClass.getDeclaredMethod("o", qpClass)
            m.isAccessible = true; m.invoke(vm, nextQp); Logger.d("jumpNext showed via o"); return
        } catch (_: Throwable) {}
        try {
            val m = vm.javaClass.getDeclaredMethod("J1", qpClass, Boolean::class.javaPrimitiveType)
            m.isAccessible = true; m.invoke(vm, nextQp, true); Logger.d("jumpNext showed via J1"); return
        } catch (_: Throwable) {}
        try {
            val m = vm.javaClass.getDeclaredMethod("O1", qpClass, String::class.java)
            m.isAccessible = true; m.invoke(vm, nextQp, ""); Logger.d("jumpNext showed via O1"); return
        } catch (_: Throwable) {}
        Logger.d("jumpNext no show method")
    }

    private fun hookDataSource(c: Class<*>) {
        val xp = xpRef ?: return
        synchronized(hookedDsClasses) {
            if (!hookedDsClasses.add(c.name)) return
        }
        Logger.d("hookDataSource: ${c.name}")
        val qpClass = try { Class.forName("com.yxcorp.gifshow.entity.QPhoto", false, c.classLoader) } catch (_: Throwable) { null }
        var batchHooked = 0; var singleHooked = 0
        for (m in c.declaredMethods) {
            val isRetList = m.returnType == java.util.List::class.java || m.returnType.name.contains("List")
            val isRetQp = qpClass != null && m.returnType == qpClass
            if ((m.name.startsWith("get") || m.name.startsWith("is")) && !isRetList && !isRetQp) continue
            Logger.d("ds method: ${m.name} ret=${m.returnType.simpleName} params=${m.parameterTypes.map { it.simpleName }}")
            val listCount = m.parameterTypes.count {
                it == java.util.List::class.java || it.name.contains("List") || it.name.contains("Collection")
            }
            if (listCount >= 2) {
                Logger.safe("hookDSBatch.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.bat.${c.name}.${m.name}").intercept { chain ->
                        try { filterListArgs(chain.args) } catch (_: Throwable) {}
                        val result = chain.proceed()
                        try { filterResult(result) } catch (_: Throwable) {}
                        result
                    }
                }
                batchHooked++; continue
            }
            if (m.returnType == java.util.List::class.java || m.returnType.name.contains("List")) {
                Logger.safe("hookDSRet.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.ret.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try { filterResult(result) } catch (_: Throwable) {}
                        result
                    }
                }
                batchHooked++; continue
            }
            if (qpClass != null && m.parameterTypes.any { it == qpClass }) {
                Logger.safe("hookDSOne.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.one.${c.name}.${m.name}").intercept { chain ->
                        try {
                            for (a in chain.args) {
                                if (a != null && a.javaClass == qpClass && shouldFilterFeed(a)) {
                                    Logger.d("feed filtered single: ${readCaption(a)?.take(30)}")
                                    return@intercept null
                                }
                            }
                        } catch (_: Throwable) {}
                        chain.proceed()
                        null
                    }
                }
                singleHooked++
            }
            if (isRetQp) {
                Logger.safe("hookDSRetQP.${m.name}") {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ds.rqp.${c.name}.${m.name}").intercept { chain ->
                        val result = chain.proceed()
                        try {
                            if (result != null && shouldFilterFeed(result)) {
                                Logger.d("feed filtered retQP: ${readCaption(result)?.take(30)}")
                                return@intercept null
                            }
                        } catch (_: Throwable) {}
                        result
                    }
                }
                singleHooked++
            }
        }
        Logger.d("hookDataSource done: ${c.name} batch=$batchHooked single=$singleHooked")
    }

    private val hookedVmClasses = mutableSetOf<String>()
    @Volatile private var lretDiagCount = 0
    private fun hookViewModel(vm: Any) {
        val xp = xpRef ?: return
        val c = vm.javaClass
        synchronized(hookedVmClasses) { if (!hookedVmClasses.add(c.name)) return }
        val qpClass = try { Class.forName("com.yxcorp.gifshow.entity.QPhoto", false, c.classLoader) } catch (_: Throwable) { null } ?: return
        Logger.d("hookViewModel: ${c.name}")
        qpClassRef = qpClass
        vmRef = vm
        var cls: Class<*>? = c
        var lvl = 0
        while (cls != null && cls != Any::class.java && lvl < 6) {
            for (m in cls!!.declaredMethods) {
                if (vmMethodDump < 200) {
                    vmMethodDump++
                    Logger.d("vmM ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                }
                if (m.returnType == Void.TYPE && m.parameterTypes.size <= 2) {
                    Logger.d("vm void: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")})")
                }
                if (m.parameterTypes.any { qpClass.isAssignableFrom(it) } && m.returnType == Void.TYPE) {
                    Logger.safe("hookVMShow.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.show.${c.name}.${m.name}").intercept { chain ->
                            try {
                                for (a in chain.args) {
                                    if (a != null && qpClass.isAssignableFrom(a.javaClass) && shouldFilterFeed(a)) {
                                        Logger.d("vm filtered show: ${readCaption(a)?.take(30)}")
                                        return@intercept null
                                    }
                                }
                            } catch (_: Throwable) {}
                            chain.proceed()
                            null
                        }
                    }
                }
                if (m.parameterTypes.any { java.util.List::class.java.isAssignableFrom(it) || it.name.contains("List") } && m.returnType == Void.TYPE) {
                    Logger.safe("hookVMList.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.list.${c.name}.${m.name}").intercept { chain ->
                            try {
                                val removed = filterListArgs(chain.args)
                                if (removed > 0) Logger.d("vm filtered list: $removed via ${m.name}")
                            } catch (_: Throwable) {}
                            chain.proceed()
                            null
                        }
                    }
                }
                // y0()/B0()/E()/F0()/H()/H0()/V0() 等返�?List 的方�?= 直播/卡片�?adapter 的数据源�?
                // 直接过滤返回值，让直播卡根本进不�?adapter�?
                if ((m.returnType == java.util.List::class.java || m.returnType.name.contains("List")) && m.parameterTypes.isEmpty()) {
                    Logger.d("vmListRet sig: ${m.name}() -> ${m.returnType.simpleName}")
                    Logger.safe("hookVMListRet.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.lret.${c.name}.${m.name}").intercept { chain ->
                            val r = chain.proceed()
                            try {
                                if (r is List<*> && r.isNotEmpty()) {
                                    val before = r.size
                                    filterResult(r)
                                    if (r.size != before) {
                                        Logger.d("vm lret ${m.name} filtered: $before -> ${r.size}")
                                        // ★ 快照诊断：若同一方法反复出现相同 before（如反复 7->2），
                                        // 说明 V0() 每次返回新建快照，删快照无效，真源在别处。
                                        lretDiagCount++
                                        if (lretDiagCount <= 6) {
                                            val implCls = r.javaClass.name
                                            val firstEl = r.firstOrNull()
                                            Logger.always("lretDIAG ${m.name}: impl=$implCls idHc=${System.identityHashCode(r)} size=${r.size} firstEl=${firstEl?.javaClass?.name ?: "null"}")
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}
                            r
                        }
                    }
                }
                // ★ rerank 插卡唯一入口 T1(int,QPhoto,boolean,String)（LiveRerankPresenter d.G
                // → VM.T1 → data_source_service.q() 单条插入）：非 List 批次 filterListArgs
                // 结构性拦不到，data source 内部列表也不在 laFind 根集——T1 入口是唯一拦点。
                // 判定用 shouldFilterFeed + decideFeedRaw 同步全量兜底（T1 一次性入口不能走
                // 异步缓存：miss 先放行=卡必上屏）；T1CALL 无条件打日志取证 T1 是否真被调用
                if (m.name == "T1" && m.parameterTypes.size == 4 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    qpClass.isAssignableFrom(m.parameterTypes[1]) &&
                    m.parameterTypes[2] == java.lang.Boolean.TYPE &&
                    m.parameterTypes[3] == String::class.java) {
                    Logger.safe("hookVMT1") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.t1.${c.name}").intercept { chain ->
                            try {
                                val t1idx = chain.args.getOrNull(0) as? Int ?: -1
                                val t1tag = chain.args.getOrNull(3) as? String ?: ""
                                val t1qp = chain.args.getOrNull(1)
                                val t1dirty = t1qp != null && (shouldFilterFeed(t1qp) || try { decideFeedRaw(t1qp) } catch (_: Throwable) { false })
                                Logger.always("T1CALL idx=$t1idx tag=$t1tag dirty=$t1dirty liveTop=$liveTop qp=${t1qp?.javaClass?.simpleName ?: "null"}")
                                if (t1dirty) {
                                    Logger.always("T1 SWALLOWED idx=$t1idx tag=$t1tag")
                                    return@intercept null
                                }
                            } catch (_: Throwable) {}
                            chain.proceed()
                            null
                        }
                    }
                }
                if (m.returnType == qpClass && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    Logger.d("vmGet sig: ${m.name}(int) -> ${m.returnType.simpleName}")
                    Logger.safe("hookVMGet.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.get.${c.name}.${m.name}").intercept { chain ->
                            val r = try { chain.proceed() } catch (_: Throwable) { null }
                            try {
                                if (r != null && qpClass.isAssignableFrom(r.javaClass)) {
                                    val idx = (chain.args.getOrNull(0) as? Int) ?: -1
                                    var clsQ: Any? = r
                                    // 空壳实例兜底：用富数据实例分�?
                                    if (readUserName(r, Reflect.readAny(r, "mEntity") ?: r).isEmpty()) {
                                        clsQ = findWindowQp(idx) ?: r
                                    }
                                    if (clsQ != null && shouldFilterFeed(clsQ)) {
                                        val clean = pickFromQueue()
                                        if (clean != null) {
                                            if (vmGetSubCount < 20) { vmGetSubCount++; Logger.d("vm getter ${m.name} -> clean: ${readCaption(clsQ)?.take(18)}") }
                                            return@intercept clean
                                        }
                                    }
                                }
                            } catch (_: Throwable) {}
                            r
                        }
                    }
                }
                if (m.name == "y" || m.name == "y0") {
                    Logger.d("vmY hook: ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(",")}) -> ${m.returnType.simpleName}")
                    Logger.safe("hookVMY.${m.name}") {
                        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("vm.y.${c.name}.${m.name}").intercept { chain ->
                            try {
                                val removed = filterListArgs(chain.args)
                                if (removed > 0) Logger.d("vmY filtered list: $removed")
                            } catch (_: Throwable) {}
                            val r = chain.proceed()
                            try {
                                if (vmYDiag < 20) {
                                    vmYDiag++
                                    Logger.d("vmY ret: ${m.name} -> ${r?.javaClass?.name ?: "null"}")
                                }
                            } catch (_: Throwable) {}
                            try { filterResult(r) } catch (_: Throwable) {}


                            r
                        }
                    }
                }
            }
            cls = cls.superclass; lvl++
        }
    }

    private var vmYDiag = 0

    private var vmListDiagDone = false
    private var vmListElDump = 0


    private var vmAllFieldsDumped = false
    @Volatile private var lastViewSig = ""
    // ★ 时间节流：全对象图 BFS（vmDeepClean＋一层列表）开销最大，hook 触发频率
    // 远超数据更新频率，500ms 窗口内重复清洗是无用功
    @Volatile private var lastFilterVmListsAt = 0L
    @Volatile private var vmRefProbeDone = false
    // ★ 治本 ANR：单次全对象图清洗成本数秒（启动期全 cache miss 放大），主线程跑
    // 必卡输入（01:05 ANR trace 铁证 main Runnable at decideFeedRaw←filterVmLists）。
    // 移后台单线程：主线程 hook 只剩入队；VM 列表多为 CopyOnWriteArrayList（快照
    // 迭代器并发安全），清洗循环全有 try-catch 兜底。节流 500ms 防后台积压
    private val cleanExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ManJiaoClean").apply { isDaemon = true }
    }
    private fun filterVmLists(obj: Any) {
        if (liveTop) return
        val now = System.currentTimeMillis()
        if (now - lastFilterVmListsAt < 500) return
        lastFilterVmListsAt = now
        // 一次性探针：确认 vmRef 状态与真源清洗是否激活（查「大青蜜桃」在屏滞留）
        if (!vmRefProbeDone) { vmRefProbeDone = true; Logger.always("VMPROBE filterVmLists armed: vm=${obj.javaClass.name}") }
        cleanExecutor.execute { filterVmListsInner(obj) }
    }
    private fun filterVmListsInner(obj: Any) {
        Logger.safe("filterVmLists") {
            // 一次性全字段 dump：找 QPhoto 类型字段的真实藏身处
            if (!vmAllFieldsDumped) {
                vmAllFieldsDumped = true
                val qpClass = qpClassRef
                val sb = StringBuilder("vmFields ${obj.javaClass.simpleName}:")
                var c0: Class<*>? = obj.javaClass
                var l0 = 0
                while (c0 != null && c0 != Any::class.java && l0 < 5) {
                    for (f0 in c0!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f0.modifiers)) continue
                        try {
                            f0.isAccessible = true
                            val v0 = f0.get(obj)
                            val isQpList = v0 is List<*> && v0.isNotEmpty() && v0[0] != null && qpClass != null && qpClass.isAssignableFrom(v0[0]!!.javaClass)
                            val mark = if (isQpList) " <<<QPLIST" else ""
                            sb.append(" ${f0.name}:${v0?.javaClass?.simpleName ?: "null"}$mark")
                        } catch (_: Throwable) {}
                    }
                    c0 = c0.superclass; l0++
                }
                Logger.d(sb.toString())
            }
            // ★ 二层清洗：VM.l.a（u.a）类嵌套 QP/槽位列表——V0() 快照的真源，每秒随 autoSkip 清洗。
            // s0$b 是槽位包装（QP 提取需 findQpInObject）；脏项换干净缓存项，保留最后 1 项防崩。
            Logger.safe("vmDeepClean") {
                val qpClass = qpClassRef
                var c1: Class<*>? = obj.javaClass
                var l1 = 0
                while (c1 != null && c1 != Any::class.java && l1 < 5) {
                    for (f1 in c1!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f1.modifiers)) continue
                        try {
                            f1.isAccessible = true
                            val v1 = f1.get(obj) ?: continue
                            val vn = v1.javaClass.name
                            if (vn.startsWith("java.") || vn.startsWith("android.") || v1 is List<*> || v1 is android.view.View) continue
                            var c2: Class<*>? = v1.javaClass
                            var l2 = 0
                            while (c2 != null && c2 != Any::class.java && l2 < 3) {
                                for (f2 in c2!!.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(f2.modifiers)) continue
                                    try {
                                        f2.isAccessible = true
                                        val v2 = f2.get(v1)
                                        // ★ LAWATCH：l.a 是 COW 真源（sanitize deep:l.a 实证），
                                        // 捕获引用 + 挂写方法 watch hook，定位快手合并新批次的
                                        // 私有路径 → 做前置删除（消灭「先上屏后删」窗口）
                                        if (f1.name == "l" && f2.name == "a" && v2 is java.util.concurrent.CopyOnWriteArrayList<*>) {
                                            armLaWatch(v2)
                                        }
                                        if (v2 is MutableList<*> && v2.isNotEmpty()) {
                                            val firstEl = v2[0]
                                            val qpDirect = firstEl != null && qpClass != null && qpClass.isAssignableFrom(firstEl.javaClass)
                                            val qpWrapped = firstEl != null && !qpDirect && findQpInObject(firstEl) != null
                                            val hasQp = qpDirect || qpWrapped
                                            // 一次性结构 dump（首元素字段图）
                                            if (hasQp && vmListElDump < 3) {
                                                vmListElDump++
                                                val fe = firstEl!!
                                                val sb2 = StringBuilder("vmDeepList ${f1.name}.${f2.name} size=${v2.size} qpDirect=$qpDirect cls=${fe.javaClass.name}:")
                                                var ec4: Class<*>? = fe.javaClass
                                                var l4 = 0
                                                while (ec4 != null && ec4 != Any::class.java && l4 < 2) {
                                                    for (ef4 in ec4!!.declaredFields.take(8)) {
                                                        if (java.lang.reflect.Modifier.isStatic(ef4.modifiers)) continue
                                                        try {
                                                            ef4.isAccessible = true
                                                            val ev4 = ef4.get(fe)
                                                            sb2.append(" ${ef4.name}=${ev4?.javaClass?.simpleName ?: "null"}")
                                                        } catch (_: Throwable) {}
                                                    }
                                                    ec4 = ec4.superclass; l4++
                                                }
                                                Logger.always(sb2.toString())
                                            }
                                            if (!hasQp) {
                                                // ★ s0$b 包装提不出 QP（mEntity 是 VideoFeed 非 QPhoto）
                                                // → hasQp 恒 false → l.a 真源从未被清洗（实证：V0 快照
                                                // 每次过滤 11 项而真源不动，广告上屏源）。元素本身可判脏
                                                // （decideFeedRaw 读 mEntity）——sanitizeList 同款兜底；
                                                // Presenter/Callback 守卫前置（l.c 回调表红线教训）
                                                val rawCls0 = firstEl?.javaClass?.name ?: continue
                                                if (rawCls0.contains("Presenter") || rawCls0.contains("Callback")) continue
                                                @Suppress("UNCHECKED_CAST")
                                                sanitizeList(v2 as MutableList<Any?>, "deep:${f1.name}.${f2.name}")
                                                continue
                                            }
                                            // ★ 回调表保护：l.c 元素是 MilanoAttachCallbackPresenter$a
                                            // （回调注册表），深层引用 QPhoto 被误判为 QP 包装列表——
                                            // 实证清洗它会破坏快手功能，类名含 Presenter 直接跳过
                                            val elCls = firstEl?.javaClass?.name ?: continue
                                            if (elCls.contains("Presenter") || elCls.contains("Callback")) continue
                                            // 直接删除式清洗：仅 qpDirect（元素本身是 QP）允许删项；
                                            // qpWrapped（QP 深藏包装）只替换不删——包装列表可能是
                                            // pager 骨架结构（i 槽位链教训），删项=破坏结构
                                            @Suppress("UNCHECKED_CAST")
                                            val m2 = v2 as MutableList<Any?>
                                            var sw2 = 0
                                            var id2 = 0
                                            while (id2 < m2.size) {
                                                val el2 = m2[id2]
                                                if (el2 == null) { m2.removeAt(id2); sw2++; continue }
                                                val qp2 = if (qpDirect) el2 else findQpInObject(el2)
                                                if (qp2 == null || !shouldFilterContent(qp2)) { id2++; continue }
                                                // 直接删除：仅 qpDirect（元素本身是 QP）允许删项；
                                                // qpWrapped（QP 深藏包装）不删——包装列表可能是
                                                // pager 骨架结构（i 槽位链教训），删项=破坏结构
                                                if (qpDirect) { m2.removeAt(id2); sw2++ }
                                                else id2++
                                            }
                                            if (sw2 > 0) Logger.always("vmDeepClean ${f1.name}.${f2.name}: swapped/removed $sw2 (left ${m2.size})")
                                        }
                                    } catch (_: Throwable) {}
                                }
                                c2 = c2.superclass; l2++
                            }
                        } catch (_: Throwable) {}
                    }
                    c1 = c1.superclass; l1++
                }
            }
            var c: Class<*>? = obj.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 4) {
                for (f in c!!.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue

                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v is MutableList<*> && v.isNotEmpty()) {
                            if (!vmListDiagDone) {
                                vmListDiagDone = true
                                val e0 = v[0]
                                Logger.d("vmListDiag ${f.name}: size=${v.size} elem=${e0?.javaClass?.name} qpIn=${e0?.let { findQpInObject(it) != null }}")
                                if (e0 != null) {
                                    val esb = StringBuilder()
                                    var ec: Class<*>? = e0.javaClass
                                    var el = 0
                                    while (ec != null && ec != Any::class.java && el < 2) {
                                        for (ef in ec!!.declaredFields.take(8)) {
                                            if (java.lang.reflect.Modifier.isStatic(ef.modifiers)) continue
                                            try {
                                                ef.isAccessible = true
                                                val ev = ef.get(e0)
                                                val ed = if (ev is List<*>) "List(${ev.size})" else ev?.javaClass?.simpleName ?: "null"
                                                esb.append("${ef.name}:${ed} ")
                                            } catch (_: Throwable) {}
                                        }
                                        ec = ec.superclass; el++
                                    }
                                    Logger.d("vmListDiag elem fields: $esb")
                                }
                            }
                            // ★ 替换式清洗：脏项优先换干净缓存项；缓存空时直接删除脏项——
                            // 不留脏项保底（删空列表也比看广告强，空页由快手 GrootEmptyFragment 兜底，
                            // triggerRefresh 拉新数据后自动恢复）。索引用 while 手动推进：
                            // 删除后元素前移不递增 idx，替换/保留才递增，防越界跳项
                            @Suppress("UNCHECKED_CAST")
                            val mutable = v as MutableList<Any?>
                            var swapped = 0
                            var idx = 0
                            while (idx < mutable.size) {
                                val el = mutable[idx]
                                if (el == null) { mutable.removeAt(idx); swapped++; continue }
                                var qp = findQpInObject(el)
                                // ★ 槽位包装链 m.b 提 QP：el(x5i.q$a).b→x5i.q.b→Fragment.m.b→QPhoto。
                                // findQpInObject 在 depth<1 才下钻提不到，这里两跳直达。
                                // 已知风险：x5i.q$b 的 b 字段也可能直接指向 Fragment（slotScan 实证两种包装都存在），
                                // 故 b 链上每跳都做 Fragment/QP 类型校验，提不到就交给 BFS 兜底
                                if (qp == null) qp = findSlotQpViaMb(el)
                                // qp==null 兜底：as8.f$b 等 pager 槽位包装类（QP 藏 2+ 层深），
                                // 用 findDirtyEntityInHolder 深度 BFS 找 Live/Ad 实体判定
                                val isDirty = if (qp != null) shouldFilterContent(qp)
                                    else findDirtyEntityInHolder(el) != null
                                if (qp == null && !isDirty && vmListElDump < 3) {
                                    vmListElDump++
                                    val sb = StringBuilder("vmElDump ${f.name}[$idx] cls=${el.javaClass.name}:")
                                    var ec2: Class<*>? = el.javaClass
                                    var l2 = 0
                                    while (ec2 != null && ec2 != Any::class.java && l2 < 3) {
                                        for (ef2 in ec2!!.declaredFields) {
                                            if (java.lang.reflect.Modifier.isStatic(ef2.modifiers)) continue
                                            try {
                                                ef2.isAccessible = true
                                                val ev2 = ef2.get(el)
                                                sb.append(" ${ef2.name}=${ev2?.javaClass?.simpleName ?: "null"}")
                                                if (ev2 is List<*> && ev2.isNotEmpty()) {
                                                    sb.append("[0]=${ev2[0]?.javaClass?.name ?: "null"}")
                                                }
                                            } catch (_: Throwable) {}
                                        }
                                        ec2 = ec2.superclass; l2++
                                    }
                                    Logger.d(sb.toString())
                                }
                                if (!isDirty) { idx++; continue }
                                // 直接删除：有 QP 判脏依据（qp!=null）才删——
                                // qp==null 靠 BFS 命中的项可能是 pager 骨架/回调结构（l.c 教训），
                                // 删结构会破坏快手功能，只跳过不删
                                if (qp != null) { mutable.removeAt(idx); swapped++ }
                                else idx++
                            }
                            if (swapped > 0) Logger.always("vmListClean ${f.name}: swapped/removed $swapped (left ${mutable.size})")

                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
        }
    }


    // ★ 槽位包装链提 QP（通用版）：包装链深度动态（实证 x5i.q$a→q→Fragment 两跳、
    // loh.f1→e1→d0→Fragment 三跳），固定跳数必失效。沿 b 字段链下钻（≤5 层），
    // 链上任一点直接命中 QP 即返回；到 Fragment 后再试 m.b。
    // 校验 b 值非集合/View，防误下钻
    private fun findSlotQpViaMb(el: Any): Any? {
        val qpClass = qpClassRef ?: return null
        try {
            var cur: Any = el
            for (hop in 0 until 5) {
                if (qpClass.isAssignableFrom(cur.javaClass)) return cur
                if (cur.javaClass.name.endsWith("Fragment")) {
                    val m = Reflect.readAny(cur, "m") ?: return null
                    val qp = Reflect.readAny(m, "b") ?: return null
                    return if (qpClass.isAssignableFrom(qp.javaClass)) qp else null
                }
                val nxt = Reflect.readAny(cur, "b") ?: return null
                if (nxt is Collection<*> || nxt is android.view.View) return null
                cur = nxt
            }
        } catch (_: Throwable) {}
        return null
    }


    private fun filterListArgs(args: List<Any?>): Int {
        if (liveTop) return 0
        var removed = 0
        for (a in args) {
            if (a is MutableList<*>) {
                val hits = a.filter { it != null && shouldFilterFeed(it) }
                // 绝不清空：多元素列表保留最后一条，防 adapter 数据列表空导致崩溃。
                // ★ 例外：单条目全脏批次（实测 LADUMP size=1 {LiveStreamFeed=1} 3次）护栏必放行
                // → 直播上屏后后台补剔晚于视图挂载。size=1 时删空=方法收到空列表=追加语义下
                // 不插入=最上游拦截；清空后由下方 prefetch(阈值4)+快手翻页填补
                if (hits.isNotEmpty() && (a.size - hits.size >= 1 || a.size == 1)) {
                    val cap0 = readCaption(hits.first())
                    // ★ 直接删除脏项不补位：补位池耗尽后轮转退化会反复取同一条旧视频=重复刷到。
                    // 列表短暂缩水由 prefetch(阈值4)+快手自身翻页填补，无重复
                    var deleted = 0
                    for (h in hits) {
                        (a as MutableList<Any?>).remove(h); deleted++
                    }
                    removed += deleted
                    Logger.d("feed filtered del=$deleted left=${a.size} first: ${cap0?.take(30)}")
                    // 调用链取证（限流）：直播卡片「先渲染后删除」漏拦路径定位用
                    if (fltCallerDiag < 20) {
                        fltCallerDiag++
                        Logger.d("fltCaller: " + Thread.currentThread().stackTrace.drop(2).take(8)
                            .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName })
                    }
                    // 列表偏短就提前预取下一页（阈值 4：只在真快耗尽才刷新，避免每批都触发刷新带回旧推荐=重复视频）
                    if (!Prefs.bool(Prefs.K_FLT_NOMORE, true)) {
                        Logger.always("prefetch BLOCKED by K_FLT_NOMORE=false (switch off!)")
                    } else if (a.size < 4) { Logger.d("prefetch short list=${a.size}"); triggerLoadMore() }
                }
            }
        }
        // ★ 不再普通过滤命中即 triggerRefresh：refresh 会重载 adapter 数据，打断
        // ViewPager 滑动动画（视频瞬切无过渡，AI 判定扩容后命中量大增放大此问题）。
        // 补位交给两处：列表短于阈值时的 prefetch(L3580) + 快手自身翻页加载。
        return removed
    }


    private var filterResultDiag = 0
    private var fltCallerDiag = 0
    // ★ 时间节流：V0() 等 getter 每次返回重建的快照列表，副本删了真源不动，
    // 每次进来都重扫重删（实证 1.45s 15 次 sanitize ret）＝纯无用功＋分配风暴。
    // 数据更新频率低，脏项最长存活 200ms 可接受
    @Volatile private var lastFilterResultAt = 0L
    private fun filterResult(result: Any?): Int {
        if (result !is MutableList<*>) return 0
        val now = System.currentTimeMillis()
        if (now - lastFilterResultAt < 200) return 0
        lastFilterResultAt = now
        if (result.isNotEmpty()) {
            filterResultDiag++
            if (filterResultDiag <= 10 || filterResultDiag % 100 == 0) {
                val elem = result[0]
                val elemCls = elem?.javaClass?.name ?: "null"
                val ent = elem?.let { Reflect.readAny(it, "mEntity") }
                val entCls = ent?.javaClass?.name ?: "null"
                Logger.d("filterResult diag #$filterResultDiag: size=${result.size} elemCls=$elemCls entCls=$entCls")
            }
        }
        val hits = result.filter { it != null && (try { shouldFilterFeed(it) } catch (_: Throwable) { false }) }
        if (hits.isEmpty()) return 0
        val cap0 = readCaption(hits.first())
        // 记录快照删掉的脏元素（身份反查真源字段用）
        try {
            synchronized(retDelQp) {
                retDelQp.clear()
                for (h in hits) retDelQp.add(h)
                if (retDelQp.size > 24) retDelQp.subList(0, retDelQp.size - 24).clear()
            }
        } catch (_: Throwable) {}
        @Suppress("UNCHECKED_CAST")
        sanitizeList(result as MutableList<Any?>, "ret")
        Logger.d("feed filtered ret ${hits.size} first: ${cap0?.take(30)}")
        // ★ 真源清洗补链：ret 是 V0() 重建的快照副本，删了真源不动（实证「大青蜜桃」直播
        // 卡 ret 删 190 轮仍在屏）。快照删到脏项=真源必有对应脏对象，此处补调 filterVmLists
        // （500ms 节流+后台线程+Presenter/Callback 守卫齐全），vmRef 已建立时同步清真源
        if (vmRef != null) { try { filterVmLists(vmRef!!) } catch (_: Throwable) {} }
        // 幸存者入�?
        try {
            for (el in result) el?.let { e -> findQpInObject(e)?.let { q -> if (!shouldFilterFeed(q)) offerClean(q) } }
        } catch (_: Throwable) {}
        // ★ 不 triggerRefresh（防滑动动画被打断，同 filterListArgs）

        return hits.size
    }

    private var lastRefreshTime = 0L
    private var lastAllDirtyRefreshAt = 0L

    private var respFieldDiag = 0
    private var respFieldCallDiag = 0
    private fun filterResponseFields(obj: Any) {
        if (obj == null) {
            if (respFieldCallDiag < 8) { respFieldCallDiag++; Logger.d("respFieldCall obj=NULL") }
            return
        }
        if (respFieldCallDiag < 8) {
            respFieldCallDiag++
            var listCount = 0
            var c0: Class<*>? = obj.javaClass
            while (c0 != null && c0 != Any::class.java) {
                for (ff in c0.declaredFields) if (ff.type == java.util.List::class.java || ff.type.name.contains("List")) listCount++
                c0 = c0.superclass
            }
            Logger.d("respFieldCall obj=${obj.javaClass.simpleName} listFields=$listCount")
        }
        var c: Class<*>? = obj.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (f.type != java.util.List::class.java && !f.type.name.contains("List")) continue
                try {
                    f.isAccessible = true
                    val list = f.get(obj) as? List<*> ?: continue
                    if (list.isEmpty()) continue
                    val e0 = list[0]
                    // 只要能抽�?QPhoto 就视�?feed 列表（含 wrapper 包装�?
                    val isFeed = e0 != null && (findQpInObject(e0) != null || e0.javaClass.name.contains("Feed") || e0.javaClass.name.contains("Photo"))
                    if (!isFeed) continue
                    if (respFieldDiag < 20) {
                        respFieldDiag++
                        Logger.d("respField see ${f.name} size=${list.size} elem0=${e0?.javaClass?.name ?: "null"}")
                    }
                    val copy = arrayListOf<Any?>()
                    copy.addAll(list)
                    sanitizeList(copy, "field:${f.name}")
                    try { f.set(obj, copy) } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
    }
    private fun sanitizeList(list: MutableList<Any?>, tag: String, allowEmpty: Boolean = false) {
        val dirtyIdx = arrayListOf<Int>()
        val originalSize = list.size
        val now = System.currentTimeMillis()
        for (i in list.indices) {
            val it = list[i] ?: continue
            val dirty = try {
                val q = findQpInObject(it) ?: it
                // 兼容裸实体（LiveStreamFeed/广告实体无 mEntity 包装）：按类名兜底（受对应开关控制）
                val rawCls = it.javaClass.name
                shouldFilterFeed(q) ||
                    (Prefs.bool(Prefs.K_FLT_LIVE, false) && rawCls.contains("LiveStreamFeed")) ||
                    (Prefs.bool(Prefs.K_FLT_ADS, false) && rawCls.contains("AdFeed")) ||
                    (Prefs.bool(Prefs.K_FLT_LIVE, false) && rawCls.contains("Live", true))
            } catch (_: Throwable) { false }
            if (dirty) dirtyIdx.add(i)
        }
        if (dirtyIdx.isEmpty()) return
        var removed = 0
        for (i in dirtyIdx.sortedDescending()) {
            // 直播位置替换成 VideoFeed 会触发 onMeasure ClassCastException，优先直接删除。
            // allowEmpty（真源存储列表，非 pager 直接数据结构）删到 0 也不崩
            val isLive = try { val rawCls = list[i]?.javaClass?.name ?: ""; rawCls.contains("LiveStreamFeed") || rawCls.contains("Live", true) } catch (_: Throwable) { false }
            if (isLive && (allowEmpty || list.size > 1)) {
                list.removeAt(i); removed++
            } else if (allowEmpty || list.size > 1) {
                list.removeAt(i); removed++
            }
        }
        if (removed > 0) {
            Logger.d("sanitize $tag removed/replaced $removed (left ${list.size})")
            // ★ 不 triggerRefresh（防滑动动画被打断，同 filterListArgs）
            // 全脏批次兜底：过滤后仍剩脏项（无干净替换可用、最后1项无法移除）→ 功能性刷新
            // 拉新批次，直到有干净视频进来（"开屏前几个全广告"场景的唯一出路）
            // 受「优化无更多视频」开关控制（与 prefetch 同一功能语义）
            if (Prefs.bool(Prefs.K_FLT_NOMORE, true) && list.isNotEmpty()) {
                val leftoverDirty = list.any { el ->
                    el != null && try {
                        val q = findQpInObject(el) ?: el
                        shouldFilterFeed(q)
                    } catch (_: Throwable) { false }
                }
                if (leftoverDirty && now - lastAllDirtyRefreshAt > 3000) {
                    lastAllDirtyRefreshAt = now
                    Logger.always("sanitize $tag all-dirty batch -> triggerRefresh (left ${list.size})")
                    triggerRefresh()
                }
            }
        }
    }


    // ★ prefetch 续拉：refresh()=invalidate+重拉第一页（带回旧推荐=重复视频）；load() 不 invalidate，
    // 已有页缓存非空时 i()=false → q1.T1() 用 j0().mCursor 续拉下一页（kik.i 接口方法，o0 实现）。
    // getMethod 解析全继承链 public 方法（knh.b 链深，declaredMethods 逐级遍历够不到 kik.o0）；
    // 失败再按 BOOTFLUSH 路径2 在字段值(如 dnh.q1)上找 load。
    // ★ 死穴防护（2026-09-08 用户报「长时间无更多滑不出」）：o0.load() 在 hasMore()==false 时是 no-op
    // （源码仅 hasMore||invalidate 才 R1 发请求），续拉链路会永久卡死——前置健康检查：
    // hasMore=false → 调 q1.refresh()（invalidate+重拉第一页）恢复供给；isLoading=true（请求在途）→ 跳过等回调；
    // 正常 → load() 续拉。
    private var lastLoadMoreTime = 0L
    private fun findLoadTarget(inst: Any): Any? {
        val selfHas = try { inst.javaClass.getMethod("load"); true } catch (_: Throwable) { false }
        if (selfHas) return inst
        var fc: Class<*>? = inst.javaClass
        var flvl = 0
        while (fc != null && fc != Any::class.java && flvl < 6) {
            for (f in fc!!.declaredFields) {
                try {
                    f.isAccessible = true
                    val req = f.get(inst) ?: continue
                    if (req is Collection<*> || req is android.view.View) continue
                    val has = try { req.javaClass.getMethod("load"); true } catch (_: Throwable) { false }
                    if (has) return req
                } catch (_: Throwable) {}
            }
            fc = fc.superclass; flvl++
        }
        return null
    }
    private fun triggerLoadMore(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastLoadMoreTime < 800) return false
        val inst = knhbInst?.get()
        if (inst == null) { Logger.d("loadMore SKIP: knhbInst=null -> fallback refresh"); return triggerRefresh() }
        val target = findLoadTarget(inst)
        if (target == null) { Logger.d("loadMore no target -> fallback refresh"); return triggerRefresh() }
        val hasMore = try {
            val hm = target.javaClass.getMethod("hasMore"); hm.isAccessible = true
            hm.invoke(target) as? Boolean ?: true
        } catch (_: Throwable) { true }
        if (!hasMore) {
            Logger.d("loadMore hasMore=false -> refresh recover")
            val rm = try { target.javaClass.getMethod("refresh") } catch (_: Throwable) { null }
            if (rm != null) {
                try { rm.isAccessible = true; rm.invoke(target); lastLoadMoreTime = now; return true } catch (_: Throwable) {}
            }
            return triggerRefresh()
        }
        val isLoading = try {
            val il = target.javaClass.getMethod("isLoading"); il.isAccessible = true
            il.invoke(target) as? Boolean ?: false
        } catch (_: Throwable) { false }
        if (isLoading) {

            if (now - lastLoadMoreTime > 5000) {

                Logger.always("loadMore in flight >5s -> hist reset + refresh recover")

                synchronized(seenPhotoIds) { seenPhotoIds.clear() }

                val rm = try { target.javaClass.getMethod("refresh") } catch (_: Throwable) { null }

                if (rm != null) { try { rm.isAccessible = true; rm.invoke(target); lastLoadMoreTime = now; return true } catch (_: Throwable) {} }

                return triggerRefresh()

            }

            Logger.d("loadMore skip: request in flight")

            return true

        }
        return try {
            val m = target.javaClass.getMethod("load")
            m.isAccessible = true
            m.invoke(target)
            lastLoadMoreTime = now
            Logger.d("loadMore called on ${target.javaClass.name} (hasMore=true)")
            true
        } catch (_: Throwable) { triggerRefresh() }
    }
    private fun triggerRefresh(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRefreshTime < 800) return false
        lastRefreshTime = now
        val vm = vmRef
        if (vm == null) { Logger.always("refresh SKIP: vmRef=null (VM not found yet)"); return false }
        for (name in arrayOf("v0", "B1", "C1", "E1", "K1", "W0", "X0", "Y0", "z0", "y0", "refresh", "loadMore")) {
            val m = try { vm.javaClass.getDeclaredMethod(name) } catch (_: Throwable) { null } ?: continue
            try {
                m.isAccessible = true
                m.invoke(vm)
                Logger.d("refresh called: $name")
                return true
            } catch (_: Throwable) {}
        }
        val sigKeys = arrayOf("refresh", "load", "more", "feed", "page", "fetch", "reload", "request")
        var c: Class<*>? = vm.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 3) {
            for (m in c!!.declaredMethods) {
                if (m.parameterTypes.isNotEmpty() || m.returnType != Void.TYPE) continue
                val mn = m.name.lowercase()
                if (!sigKeys.any { mn.contains(it) }) continue
                try {
                    m.isAccessible = true
                    m.invoke(vm)
                    Logger.d("refresh called(sig): ${m.name}")
                    return true
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        Logger.always("refresh no method vm=${vm.javaClass.name}")
        return false
    }

    private var adpListDumped = false
    private fun dumpAdapterLists(adp: Any) {
        if (adpListDumped) return
        adpListDumped = true
        val qpClass = qpClassRef
        fun scan(obj: Any, prefix: String, depth: Int, seen: MutableSet<Int>) {
            if (depth > 2) return
            if (!seen.add(System.identityHashCode(obj))) return
            var c: Class<*>? = obj.javaClass
            var lvl = 0
            while (c != null && c != Any::class.java && lvl < 3) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(obj)
                        if (v is List<*>) {
                            val first = v.firstOrNull()
                            val isQp = qpClass?.let { q -> first != null && q.isAssignableFrom(first.javaClass) } == true
                            Logger.always("adpList $prefix${c.simpleName}.${f.name}: size=${v.size} elem=${first?.javaClass?.name ?: "null"}${if (isQp) " <== QP" else ""}")
                        } else if (v != null && depth < 2 && !v.javaClass.name.startsWith("java.")) {
                            scan(v, "$prefix${c.simpleName}.${f.name}>", depth + 1, seen)
                        }
                    } catch (_: Throwable) {}
                }
                c = c.superclass; lvl++
            }
    fun refreshContent(): Boolean {
        synchronized(seenPhotoIds) { seenPhotoIds.clear() }
        Logger.always("refreshContent: hist cleared")
        val inst = knhbInst?.get()
        if (inst == null) { Logger.always("refreshContent: knhbInst=null, fallback loadMore"); return triggerLoadMore() }
        val target = findLoadTarget(inst)
        if (target == null) { Logger.always("refreshContent: no target, fallback loadMore"); return triggerLoadMore() }
        val rm = try { target.javaClass.getMethod("refresh") } catch (_: Throwable) { null }
        if (rm != null) {
            try {
                rm.isAccessible = true; rm.invoke(target)
                lastLoadMoreTime = System.currentTimeMillis()
                Logger.always("refreshContent: refresh() called on " + target.javaClass.name)
                return true
            } catch (e: Throwable) { Logger.always("refreshContent: refresh() threw " + e.javaClass.name) }
        }
        Logger.always("refreshContent: no refresh method, fallback loadMore")
        return triggerLoadMore()
    }
        }
        scan(adp, "", 0, mutableSetOf())
        vmRef?.let { scan(it, "VM>", 0, mutableSetOf()) }
    }


    private var feedDiagCount = 0
    private var lastFeedDiagSig = ""
    private var nonVfDiagCount = 0
    private var weakUnwrapDiag = 0
    private var liveDiagCount = 0
    private var movieDiagCount = 0
    private var entFullDumpCount = 0
    private var entScanDiag = 0
    private var entFullProbeCount = 0
    private var liveFieldDiag = 0
    // 过滤总开关缓存（2 秒 TTL）：所有过滤开关全关时 shouldFilterFeed 零反射直接返回，
    // 避免每次 feed 加载都白跑一遍 mEntity 反射链（全关时卡顿的主源）
    private var anyOnCache = false
    private var anyOnAt = 0L
    private fun anyFilterOn(): Boolean {
        val now = System.currentTimeMillis()
        if (now - anyOnAt > 2000) {
            anyOnAt = now
            anyOnCache = Prefs.bool(Prefs.K_FLT_ADS, false) || Prefs.bool(Prefs.K_FLT_ADVIDEO, false) ||
                Prefs.bool(Prefs.K_FLT_IMAGE, false) || Prefs.bool(Prefs.K_FLT_LIVE, false) ||
                Prefs.bool(Prefs.K_FLT_AI, false) || Prefs.bool(Prefs.K_FLT_EC, false) ||
                Prefs.bool(Prefs.K_FLT_DRAMA, false) || Prefs.bool(Prefs.K_FLT_LIKE_ON, false) ||
                Prefs.bool(Prefs.K_FLT_KW_ON, false)
        }
        return anyOnCache
    }

    // ★ 性能优化-判定缓存：同一 QPhoto 在列表/窗口/adapter 多条链路被重复判定几十上百次，
    // 每次全量反射 20+ 字段。WeakHashMap 按对象身份缓存判定结果，配置变化时失效。
    private val sigIdCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private val feedFilterCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
    private val contentFilterCache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
    private var fcacheDiag = 0
    fun invalidateFilterCache() {
        feedFilterCache.clear()
        contentFilterCache.clear()
    }
    fun refreshContent(): Boolean {
        synchronized(seenPhotoIds) { seenPhotoIds.clear() }
        Logger.always("refreshContent: hist cleared")
        val inst = knhbInst?.get()
        if (inst == null) { Logger.always("refreshContent: knhbInst=null, fallback loadMore"); return triggerLoadMore() }
        val target = findLoadTarget(inst)
        if (target == null) { Logger.always("refreshContent: no target, fallback loadMore"); return triggerLoadMore() }
        val rm = try { target.javaClass.getMethod("refresh") } catch (_: Throwable) { null }
        if (rm != null) {
            try {
                rm.isAccessible = true; rm.invoke(target)
                lastLoadMoreTime = System.currentTimeMillis()
                Logger.always("refreshContent: refresh() called on " + target.javaClass.name)
                return true
            } catch (e: Throwable) { Logger.always("refreshContent: refresh() threw " + e.javaClass.name) }
        }
        Logger.always("refreshContent: no refresh method, fallback loadMore")
        return triggerLoadMore()
    }
    private fun cachedDecide(cache: MutableMap<Any, Boolean>, qp: Any, decide: () -> Boolean): Boolean {
        if (!Prefs.bool(Prefs.K_PERF_FCACHE, true)) return decide()
        if (cache.size > 3000) cache.clear()
        val hit = cache[qp]
        if (hit != null) {
            if (fcacheDiag < 5) { fcacheDiag++; Logger.d("fcache hit (${cache.size})") }
            return hit
        }
        val r = decide()
        cache[qp] = r
        return r
    }

    // ★ 误伤审计：命中分支记录原因+文案样本；每 60 次汇总一次各规则拦截量
    private val filterHitStats = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var hitLogDiag = 0
    private var quickDebugCount = 0
    private var hitTotal = 0
    private fun hit(reason: String, qp: Any) {
        try {
            filterHitStats.merge(reason, 1, Int::plus)
            hitTotal++
            if (hitLogDiag < 60) {
                hitLogDiag++
                val un = try { readUserName(qp, Reflect.readAny(qp, "mEntity") ?: qp) } catch (_: Throwable) { "" }
                Logger.d("fltHit [$reason] user=$un cap=${readCaption(qp)?.take(28)}")
            }
            if (hitTotal % 60 == 0) {
                Logger.d("fltHitStats total=$hitTotal " + filterHitStats.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}=${it.value}" })
            }
        } catch (_: Throwable) {}
    }

    // ★ 内容签名缓存：V0 等 getter 每次返回重建的包装对象（identity 变化致 cachedDecide
    // 的 identity 缓存全 miss，实证 96 万次全量判定/CPU 113% 风暴）。同一视频快照重建但
    // 内容不变 → 按内容签名（实体类|文案|点赞数）命中，全量判定每条视频只跑一次
    private val feedSigCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val contentSigCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ★ 治本 ANR 第二步：主线程零重判定。decideFeedRaw 全量判定（20+ 反射读）启动期
    // cache 全 miss 时主线程连跑 N 次=01:05 ANR 铁证。改为：sig 命中直接用；miss 时
    // 主线程只提 sig（4 次 Field 缓存反射，微秒级）＋入后台队列先放行（false）；
    // 后台算完写 sig 缓存，由高频触发的 getter/响应 hook 下一拍补剔。脏项最长存活
    // 一小拍（后台判定 ms 级+补剔频繁），换来主线程零阻塞
    private val asyncDecidePending = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    // ★ 主线程微秒级官方标记快判：decideBySig miss 时先跑此函数，命中官方标记即拦，
    // 未命中才入后台全量判定。修复 ANR 修复引入的首见漏一拍回归（AI/广告/短剧）
    private fun quickOfficialDirty(qp: Any): Boolean {
        // ★ 裸实体兜底：LiveStreamFeed 直接作列表元素时无 mEntity 字段（实测 03:45 24批次
        // 全放行直通上屏），ent 取 qp 自身让 live:entCls 类名判定照常工作
        val ent = Reflect.readAny(qp, "mEntity") ?: qp
        if (quickDebugCount < 10) { quickDebugCount++; val aiOn = Prefs.bool(Prefs.K_FLT_AI, false); val pm = Reflect.readAny(ent, "mPhotoMeta"); val dis = try { Reflect.readAny(pm, "mDisclaimergeMessageV2") } catch (_: Throwable) { null }; val disC = if (dis != null) try { Reflect.readAny(dis, "content") as? String } catch (_: Throwable) { null } else null; Logger.d("QDBG aiOn=$aiOn hasDis=${dis != null} disC=$disC") }
        // ★ 直播：ent 类名含 Live 即拦（纯类名检查微秒级，与 advideo:mAd 同级）。
        // 实证 02:36 LADUMP {LiveStreamFeed=3} 批次 del 只带走 AI/广告、3 条直播全部放行
        // ——直播此前不在 quick 路径，后台补剔又晚于 pager 构造，致精选tab直播上屏
        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && ent.javaClass.name.contains("Live", true)) {
            hit("live:entCls", qp); return true
        }
        if (Prefs.bool(Prefs.K_FLT_ADVIDEO, true)) {
            if (Reflect.readAny(ent, "mAd") != null) { hit("advideo:mAd", qp); return true }
            val adNovelObj = Reflect.readAny(ent, "mAdNovelVideoMeta")
            if (adNovelObj != null && (safeNextLong(adNovelObj, "mNovelId") > 0 || !Reflect.readString(adNovelObj, "mTitle").isNullOrBlank())) { hit("advideo:adNovel", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_AI, false)) {
            val pm = Reflect.readAny(ent, "mPhotoMeta")
            if (pm != null && Reflect.readBool(pm, "photoAiAnalyze") == true) { hit("ai:analyzeFlag", qp); return true }
            val disC = aiDisclaimerContent(pm)
            if (disC != null) { hit("ai:disclaimer \"${disC.take(18)}\"", qp); return true }
            val cm = Reflect.readAny(ent, "mCommonMeta")
            val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
            if (cap.contains("ai生成", true) || cap.contains("AI创作") || cap.contains("疑似") || cap.contains("AIGC") || cap.contains("人工智能")) { hit("ai:capText", qp); return true }
        } else {
            if (quickDebugCount < 10) { quickDebugCount++; Logger.d("QDBG aiOff pm.hasDis=${try { Reflect.readAny(Reflect.readAny(ent, "mPhotoMeta"), "mDisclaimergeMessageV2") != null } catch (_: Throwable) { false }}") }
        }
        if (Prefs.bool(Prefs.K_FLT_DRAMA, true)) {
            if (Reflect.readAny(ent, "mKwAppNativeDrama") != null) { hit("drama:kwApp", qp); return true }
            if (Reflect.readAny(ent, "mNovelDrama") != null) { hit("drama:novel", qp); return true }
            if (Reflect.readAny(ent, "mLongToShortDrama") != null) { hit("drama:longShort", qp); return true }
        }
        return false
    }
    private fun decideBySig(cache: java.util.concurrent.ConcurrentHashMap<String, Boolean>, qp: Any, decide: () -> Boolean): Boolean {
        if (!Prefs.bool(Prefs.K_PERF_FCACHE, true)) return decide()
        sigIdCache[System.identityHashCode(qp)]?.let { return it }
        val sig = try {
            val ent = Reflect.readAny(qp, "mEntity")
            val pm = ent?.let { Reflect.readAny(it, "mPhotoMeta") }
            // ★ 类名兜底用 qp 自身：裸实体（无 mEntity）若退化成空串公共 sig，
            // 首个判定结果会污染全部后续裸实体的缓存（false 放行直通上屏）
            (ent?.javaClass?.name ?: qp.javaClass.name) + "|" +
                (ent?.let { e -> Reflect.readAny(e, "mCommonMeta")?.let { Reflect.readString(it, "mCaption") } } ?: "") + "|" +
                (pm?.let { Reflect.readLong(it, "mLikeCount") } ?: -1L)
        } catch (_: Throwable) { null }
        if (sig != null) {
            cache[sig]?.let { sigIdCache[System.identityHashCode(qp)] = it; return it }
            if (cache.size > 3000) cache.clear()
            // miss：主线程先跑 quickOfficialDirty（微秒级官方标记），命中即拦；
            // 未命中入后台线程跑全量判定，先放行
            if (quickOfficialDirty(qp)) {
                cache[sig] = true
                return true
            }
            if (asyncDecidePending.add(sig)) {
                cleanExecutor.execute {
                    try {
                        val r = decide()
                        cache[sig] = r
                        sigIdCache[System.identityHashCode(qp)] = r
                        asyncDecidePending.remove(sig)
                    } catch (_: Throwable) { asyncDecidePending.remove(sig) }
                }
            }
            return false
        }
        return decide()
    }

    private fun shouldFilterFeed(qp: Any): Boolean {
        if (liveTop) return false
        if (!anyFilterOn()) return false
        // ★ 解包 WeakReference：精选 tab 列表元素是 WeakReference 包装（实证 r15d #9 elemCls=WeakReference entCls=null），
        // WeakReference 没 mEntity 字段 → ent=qp 自身 → entCls=WeakReference 不含 Live → 漏判。先 .get() 解包再判定
        val realQp = if (qp.javaClass.name == "java.lang.ref.WeakReference") {
            val inner = try { (qp as java.lang.ref.WeakReference<*>).get() } catch (_: Throwable) { null }
            if (inner == null) return false
            if (weakUnwrapDiag < 30) { weakUnwrapDiag++; Logger.d("weakUnwrap: ${inner.javaClass.name}") }
            // 解包后若非 QPhoto（如 HomeFeaturedMilanoContainerFragment 容器），从内部找 QPhoto 再判定
            val qpClass = qpClassRef
            if (qpClass != null && !qpClass.isAssignableFrom(inner.javaClass)) {
                findQpInObject(inner) ?: inner
            } else inner
        } else qp
        return decideBySig(feedSigCache, realQp) { cachedDecide(feedFilterCache, realQp) { decideFeedRaw(realQp) } }
    }
    private fun decideFeedRaw(qp: Any): Boolean {
        // ★ 裸实体兜底：同 quickOfficialDirty，无 mEntity 时判 qp 自身类名
        val ent = Reflect.readAny(qp, "mEntity") ?: qp
        val entCls = ent.javaClass.name
        if (!entCls.contains("feed.VideoFeed")) {
            if (nonVfDiagCount < 20 || nonVfDiagCount % 100 == 0) {
                nonVfDiagCount++
                Logger.d("nonVF ent: $entCls")
            } else nonVfDiagCount++
            if (Prefs.bool(Prefs.K_FLT_LIVE, false) && entCls.contains("Live", true)) {
                liveDiagCount++
                if (liveDiagCount % 100 == 1) {
                    Logger.d("live stack #${liveDiagCount}:\n" + Thread.currentThread().stackTrace.drop(1).take(16).joinToString("\n"))
                }
                if (liveDiagCount <= 3 || liveDiagCount % 100 == 0) Logger.d("live feed hit: cls=$entCls")
                try { ensureLiveFeedConstructHooked(ent) } catch (_: Throwable) {}
                return true
            }
            // ★ 游戏广告直播卡：AdNovelVideoMeta（"天龙八部"类"点击进入直播间"卡）类名不含 Live。
            // 用户定义：任何带 "进入直播间/立即参与/玩端游" 入口的卡片都算"直播"，必须刷不出来。
            if (Prefs.bool(Prefs.K_FLT_LIVE, false)) {
                val capx = (readCaption(qp) ?: Reflect.readString(ent, "mCaption"))?.take(60) ?: ""
                if (entCls.contains("AdNovel") || entCls.contains("NovelVideo") || entCls.contains("GameAd")) {
                    if (liveDiagCount % 100 == 0) Logger.d("live game-ad hit: cls=$entCls cap=${capx.take(16)}")
                    return true
                }
                val unx = readUserName(qp, ent)
                if (capx.contains("进入直播间") || capx.contains("点击进入直播") || capx.contains("立即参与") ||
                    (capx.contains("端游") && capx.contains("上线")) ||
                    unx.contains("直播") || unx.contains("弹幕游戏")) return true
            }
            return false
        }
        val cm = Reflect.readAny(ent, "mCommonMeta")
        val pm = Reflect.readAny(ent, "mPhotoMeta")
        val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
        // ★★★ 视频作者直播浮窗：普通 VideoFeed 里驱动 "直播中/进入直播间/直播小窗" 的字段
        // （欠编译版本 mCurrentLivingState 仅是其一；这里全量找 live 相关字段名）
        if (liveFieldDiag < 6) {
            liveFieldDiag++
            val hits = mutableListOf<String>()
            fun scanForLive(obj: Any, prefix: String) {
                var c: Class<*>? = obj.javaClass
                var lvl = 0
                while (c != null && c != Any::class.java && lvl < 3) {
                    for (f in c!!.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        val fnl = f.name.lowercase()
                        if (fnl.contains("live") || fnl.contains("living") || fnl.contains("streaming") || fnl.contains("livingwindow")) {
                            try {
                                f.isAccessible = true
                                val v = f.get(obj)
                                hits.add("$prefix[${c.simpleName}]${f.name}=${v?.javaClass?.simpleName ?: "null"}")
                            } catch (_: Throwable) {}
                        }
                    }
                    c = c.superclass; lvl++
                }
            }
            scanForLive(ent, "ent ")
            if (pm != null) scanForLive(pm, "pm ")
            if (cm != null) scanForLive(cm, "cm ")
            scanForLive(qp, "qp ")
            if (hits.isNotEmpty()) Logger.d("LIVEFIELD $hits cap=${cap.take(16)}")
        }
        feedDiagCount++
        // ★ 性能：整段诊断反射（20+ 次）只为拼日志，quiet 时全跳过；但
        // lastViewQp 换条更新是 AIFULL 兜底锚点，quiet 时也必须维护
        val un = readUserName(qp, ent)
        val like = pm?.let { Reflect.readLong(it, "mLikeCount") } ?: -1L
        val sig = cap.take(22) + "|" + like + "|" + un
        if (sig != lastViewSig) { lastViewSig = sig; lastViewQp = qp }
        if (!Logger.quiet && (feedDiagCount <= 10 || sig != lastFeedDiagSig)) {
            lastFeedDiagSig = sig
            val cmt = pm?.let { Reflect.readLong(it, "mCommentCount") } ?: -1L
            val liveMeta = Reflect.readAny(ent, "mLivePlaybackMeta")
            val liveSid = liveMeta?.let { Reflect.readAny(it, "mLiveStreamId") }
            val drama1 = Reflect.readAny(ent, "mKwAppNativeDrama")
            val drama2 = Reflect.readAny(ent, "mNovelDrama")
            val drama3 = Reflect.readAny(ent, "mLongToShortDrama")
            val tube = Reflect.readAny(ent, "mTubeModel")
            val tubeInfo = tube?.let { Reflect.readAny(it, "mTubeInfo") }
            val tubeTag = tube?.let { Reflect.readBool(it, "mHasTubeTag") }
            val vm = Reflect.readAny(ent, "mVideoModel")
            val longVid = vm?.let { Reflect.readBool(it, "mIsLongVideo") }
            val pmCls = pm?.javaClass?.simpleName ?: "null"
            // 抖鸡式类型判别：抓快手的 awemeType 等价 int 字段�?
            val entType = safeNextLong(ent, "mFeedType")
            val entDisp = safeNextLong(ent, "mDisplayType")
            val qpType = safeNextLong(qp, "mType")
            val cmType = cm?.let { Reflect.readLong(it, "mType") } ?: -1L
            val pmType = pm?.let { Reflect.readLong(it, "mType") } ?: -1L
            val cor = Reflect.readAny(ent, "mCoronaInfo")
            val cardStyle = cor?.let { Reflect.readInt(it, "mCardStyleType") } ?: -1
            val cardPlay = cor?.let { Reflect.readInt(it, "mCardPlayType") } ?: -1
            // 审计：全特征（含作者名/类名/mAd），换条才打，便于抓漏网广告
            val mAdAny = Reflect.readAny(ent, "mAd")
            if (sig != lastViewSig || lastViewQp === qp) {
                Logger.d("VIEWDIAG like=$like cmt=$cmt user=\"$un\" ent=${ent.javaClass.simpleName} ad=${mAdAny != null} aiFields=" + dumpAiFields(qp, ent, cm) + " liveSid=${liveSid != null} d1=${drama1 != null} capLen=${cap.length} cap=\"${cap.take(80)}\"")
            }
            Logger.d("feed diag #$feedDiagCount like=$like liveSid=${liveSid != null} d1=${drama1 != null} d2=${drama2 != null} d3=${drama3 != null} tubeInfo=${tubeInfo != null} tubeTag=$tubeTag longVid=$longVid pmCls=$pmCls entType=$entType entDisp=$entDisp qpType=$qpType cmType=$cmType pmType=$pmType cardStyle=$cardStyle cardPlay=$cardPlay cap=${cap.take(18)}")
            if (entFullProbeCount < 2) { entFullProbeCount++; Logger.always("ENTPROBE hit: quiet=${Logger.quiet} longVid=$longVid d1=${drama1 != null} tube=${tube != null}") }
            // ★ ENTFULL 一次性诊断：长视频且 drama 三字段全空的样本（如电视剧剪辑
            // 「从海底出击」）。实证 tubeTag=false 打印值证明 mTubeModel 非空——
            // dump ent + tube 两层全字段找真正的 TV/剧集结构化标记来源
            if (entFullDumpCount < 3 && longVid == true && drama1 == null && drama2 == null && drama3 == null) {
                entFullDumpCount++
                fun dumpAll(o: Any, prefix: String, sb: StringBuilder) {
                    var cf: Class<*>? = o.javaClass
                    var lf = 0
                    while (cf != null && cf != Any::class.java && lf < 4) {
                        for (ff in cf!!.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(ff.modifiers)) continue
                            try {
                                ff.isAccessible = true
                                val fv = ff.get(o)
                                val fvStr = when {
                                    fv == null -> "null"
                                    fv is List<*> -> if (fv.isEmpty()) "[]" else "list[${fv.size}]"
                                    fv is String -> if ((fv as String).length > 24) (fv as String).take(24) + "…" else fv
                                    else -> fv.toString().take(24)
                                }
                                sb.append(" $prefix${ff.name}=$fvStr")
                            } catch (_: Throwable) {}
                        }
                        val sup = cf!!.superclass; cf = sup; lf++
                    }
                }
                val sb = StringBuilder("ENTFULL #$entFullDumpCount like=$like cap=\"${cap.take(12)}\" ${ent.javaClass.name}:")
                dumpAll(ent, "", sb)
                if (tube != null) { sb.append(" ||TUBE ${tube.javaClass.name}:"); dumpAll(tube, "t.", sb) }
                try { Reflect.readAny(ent, "mStandardSerialMeta")?.let { s -> sb.append(" ||SERIAL ${s.javaClass.name}:"); dumpAll(s, "s.", sb) } } catch (_: Throwable) {}
                try { Reflect.readAny(ent, "mColumnMeta")?.let { c2 -> sb.append(" ||COL ${c2.javaClass.name}:"); dumpAll(c2, "c.", sb) } } catch (_: Throwable) {}
                Logger.d(sb.toString())
            }
            // ★ ENTSCAN 深扫：找直播带货/电商 KRN 挂件在 VideoFeed 数据层的宿主字段。
            // 证据：KrnReactContainerView 的 LaunchModel.f Bundle 含 bundleId(Kwaishop*)，
            // CombinedCard cardHeight=80 横条挂件——疑似 VideoFeed 内部 meta 字段驱动。
            // BFS ent 字段树（深 4 层），凡 String 值含 krn/Kwaishop/bundleId 即报告路径。
            if (entScanDiag < 10) {
                entScanDiag++
                val scanHits = mutableListOf<String>()
                fun entScan(obj: Any?, prefix: String, depth: Int, seen: MutableSet<Int>) {
                    if (obj == null || depth > 4 || scanHits.size > 12) return
                    if (!seen.add(System.identityHashCode(obj))) return
                    var cf: Class<*>? = obj.javaClass
                    var lf = 0
                    while (cf != null && cf != Any::class.java && lf < 2) {
                        for (ff in cf!!.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(ff.modifiers)) continue
                            try {
                                ff.isAccessible = true
                                val fv = ff.get(obj)
                                if (fv == null) continue
                                if (fv is String) {
                                    if (fv.contains("krn", true) || fv.contains("Kwaishop") || fv.contains("bundleId") || fv.contains("renderUrl") || fv.contains("kwaipreviewlive")) {
                                        scanHits.add("$prefix${ff.name}='${fv.take(80)}'")
                                    }
                                } else if (fv is List<*>) {
                                    if (fv.isNotEmpty() && fv[0] != null && fv[0] !is String) entScan(fv[0], "$prefix${ff.name}[0].", depth + 1, seen)
                                } else if (fv.javaClass.name.startsWith("com.kuaishou") || fv.javaClass.name.startsWith("com.yxcorp") || fv is android.os.Bundle) {
                                    if (fv is android.os.Bundle) {
                                        for (k in fv.keySet()) {
                                            val bv = fv.get(k)
                                            if (bv is String && (bv.contains("krn", true) || bv.contains("Kwaishop") || bv.contains("bundleId"))) scanHits.add("$prefix${ff.name}.$k='${bv.take(80)}'")
                                        }
                                    } else entScan(fv, "$prefix${ff.name}.", depth + 1, seen)
                                }
                            } catch (_: Throwable) {}
                        }
                        val sup = cf!!.superclass; cf = sup; lf++
                    }
                }
                entScan(ent, "ent.", 0, java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>()))
                entScan(qp, "qp.", 0, java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>()))
                if (scanHits.isNotEmpty()) Logger.always("ENTSCAN #$entScanDiag cap=\"${cap.take(14)}\" hits=$scanHits")
            }
            if (feedDiagCount <= 5 && pm != null) Logger.d("PMDUMP #$feedDiagCount pm=${dumpKV(pm)}")
            if (pm != null) {
                val dis = try { Reflect.readAny(pm, "mDisclaimergeMessageV2") } catch (_: Throwable) { null }
                if (dis != null) Logger.d("DISDUMP #$feedDiagCount dis=${dumpKV(dis)} content=${try { Reflect.readAny(dis, "content") } catch (_: Throwable) { null }}")
            }
        }
        if (movieDiagCount < 8) {
            val tube = Reflect.readAny(ent, "mTubeModel")
            val serial = Reflect.readAny(ent, "mStandardSerialMeta")
            val adNovel = Reflect.readAny(ent, "mAdNovelVideoMeta")
            val column = Reflect.readAny(ent, "mColumnMeta")
            val liveMeta = Reflect.readAny(ent, "mLivePlaybackMeta")
            val isMovie = cap.isNotEmpty() && MOVIE_HINT.any { cap.contains(it) }
            val isLiveMeta = liveMeta != null
            val pmAi = pm?.let { Reflect.readBool(it, "photoAiAnalyze") }
            val isAiCap = cap.contains("AI", true) || cap.contains("疑似") || cap.contains("生成")
            if (isMovie || serial != null || adNovel != null || tube != null || isLiveMeta || isAiCap || pmAi == true) {
                movieDiagCount++
                val sb = StringBuilder()
                sb.append("cap=${cap.take(25)}")
                if (tube != null) sb.append(" | tube").append(dumpKV(tube))
                if (serial != null) sb.append(" | serial").append(dumpKV(serial))
                if (adNovel != null) sb.append(" | adNovel").append(dumpKV(adNovel))
                if (column != null) sb.append(" | column").append(dumpKV(column))
                if (liveMeta != null) sb.append(" | liveMeta").append(dumpKV(liveMeta))
                if (pm != null) {
                    val aiKeys = dumpKVFilter(pm, "ai")
                    if (aiKeys.isNotEmpty()) sb.append(" | pmAi=").append(aiKeys)
                }
                val vmIdx = Reflect.readAny(ent, "mVideoModel")
                if (vmIdx != null) sb.append(" | vm").append(dumpKV(vmIdx))
                Logger.d("deep #$movieDiagCount: $sb")
            }
        }
        // ★ 误伤审计：每个命中分支记录原因+文案样本，统计各规则拦截量（找出误伤大户）
        if (Prefs.bool(Prefs.K_FLT_ADVIDEO, true)) {
            if (Reflect.readAny(ent, "mAd") != null) { hit("advideo:mAd", qp); return true }
            // ★ 官方有效标记：mNovelId>0 / 内部 mTitle 非空（空壳 AdNovelVideoMeta 普通视频
            // 也挂，仅类名判定曾误杀一片；旧 GC 风暴实为 Reflect 无缓存异常风暴，已修缓存）
            val adNovelObj = Reflect.readAny(ent, "mAdNovelVideoMeta")
            if (adNovelObj != null && (safeNextLong(adNovelObj, "mNovelId") > 0 || !Reflect.readString(adNovelObj, "mTitle").isNullOrBlank())) { hit("advideo:adNovel", qp); return true }
            if (ent.javaClass.name.contains("Ad")) { hit("advideo:entCls", qp); return true }
            val adTag = Reflect.readAny(ent, "mAdLabelDescription") ?: Reflect.readAny(ent, "mTitle")
            if (adTag != null && adTag.toString().isNotBlank() && adTag.toString() != "null") {
                val src = if (Reflect.readAny(ent, "mAdLabelDescription") != null) "adLabel" else "mTitle"
                hit("advideo:$src", qp); return true
            }
        }
        if (Prefs.bool(Prefs.K_FLT_ADS, true)) {
            val capLen = cap.length
            if (capLen <= 30 && AD_TEXTS.any { cap.contains(it, true) }) { hit("ads:capText", qp); return true }
            if (cap.contains("开屏广告") || cap.contains("广告位") || cap.contains("广告时间") || cap.contains("广告：")) { hit("ads:capHard", qp); return true }
            val un = readUserName(qp, ent)
            if (un.contains("开屏广告") || un.contains("广告") || un.contains("推广") || un.contains("游戏广告")) { hit("ads:userAd", qp); return true }
            // 游戏任务/签到引流广告："极速活跃够开宝箱" "签到+双倍" "60秒升级活跃"
            if (un.contains("活跃") || un.contains("宝箱") || un.contains("极速") || un.contains("签到") || un.contains("开宝箱") || un.contains("金币")) { hit("ads:userTask", qp); return true }
            if (cap.contains("签到") && (cap.contains("活跃") || cap.contains("宝箱") || cap.contains("双倍") || cap.contains("极速") || cap.contains("升级") || cap.contains("开宝箱"))) { hit("ads:capTask", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_LIVE, false)) {
            if (ent.javaClass.name.contains("Live", true)) { hit("live:entCls", qp); return true }
            val lm = Reflect.readAny(ent, "mLivePlaybackMeta")
            if (lm != null && Reflect.readAny(lm, "mLiveStreamId") != null) { hit("live:meta", qp); return true }
            if (pm != null && Reflect.readBool(pm, "mCurrentLivingState") == true) { hit("live:state", qp); return true }
            // 视频广告 + 进入直播间入口（普�?VideoFeed，无 Live 类）：靠入口文案识别
            if (cap.contains("进入直播间") || cap.contains("点击进入直播") || cap.contains("直播中") || cap.contains("提现") || cap.contains("入账") || cap.contains("任务奖励")) { hit("live:capText", qp); return true }
            val unLive = readUserName(qp, ent)
            if (unLive.contains("虚拟") || unLive.contains("直播")) { hit("live:userName", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_IMAGE, false)) {
            // 正向精准：实体类名 ImageFeed/Atlas（图集数据链路实测）或 mImageModel 存在
            val entClsName = ent.javaClass.name
            if (entClsName.contains("ImageFeed") || entClsName.contains("Atlas")) { hit("image:entCls", qp); return true }
            if (Reflect.readAny(ent, "mImageModel") != null) { hit("image:mImageModel", qp); return true }
            val type = cm?.let { Reflect.readLong(it, "mType") } ?: 0L
            if (type == 2L) { hit("image:cmType2", qp); return true }
            if (pm != null && (Reflect.readBool(pm, "mHasAtlasText") == true || Reflect.readAny(pm, "mAtlasDetailTitle") != null)) { hit("image:atlasMeta", qp); return true }
            // 兜底：无视频模型 → 图文
            val vm = Reflect.readAny(ent, "mVideoModel")
            if (vm == null || Reflect.readAny(vm, "mVideoUrl") == null) { hit("image:noVideoUrl", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_AI, false)) {
            if (pm != null && Reflect.readBool(pm, "photoAiAnalyze") == true) { hit("ai:analyzeFlag", qp); return true }
            if (Reflect.readBool(ent, "mAiTagForAuthor") == true) { hit("ai:tagBool", qp); return true }
            val aiTagStr = try { Reflect.readAny(ent, "mAiTagForAuthor")?.toString() } catch (_: Throwable) { null }
            if (aiTagStr != null && aiTagStr.isNotBlank() && aiTagStr != "false" && aiTagStr != "0") { hit("ai:tagStr", qp); return true }
            // "AI" 大小写敏感匹配：ignoreCase 会命中英文单词里的 ai（wait/rain/main）误伤正常视频
            // "ai生成"/"AI创作" 忽略大小写安全：ai/AI 后紧跟中文，英文单词不可能出现该组合
            if (cap.contains("ai生成", true) || cap.contains("AI创作") || cap.contains("疑似") || cap.contains("AIGC") || cap.contains("人工智能") || Regex("\\bAI\\b|AI[生成制作绘画]|:AI|AI：").containsMatchIn(cap)) { hit("ai:capText", qp); return true }
            // ★ 官方作者声明 AI 标记（数据层铁证路径：caption 无标签也能拦，「刷不到」关键）
            val disC = aiDisclaimerContent(pm)
            if (disC != null) { hit("ai:disclaimer \"${disC.take(18)}\"", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_DRAMA, true)) {
            if (Reflect.readAny(ent, "mKwAppNativeDrama") != null) { hit("drama:kwAppNative", qp); return true }
            if (Reflect.readAny(ent, "mNovelDrama") != null) { hit("drama:novel", qp); return true }
            if (Reflect.readAny(ent, "mLongToShortDrama") != null) { hit("drama:long2short", qp); return true }
            if (dramaShellReal(ent)) { hit("drama:shell", qp); return true }
            val tube = Reflect.readAny(ent, "mTubeModel")
            if (tube != null && (Reflect.readAny(tube, "mTubeInfo") != null || Reflect.readBool(tube, "mHasTubeTag") == true)) { hit("drama:tube", qp); return true }
            // ★ 电视剧标签修复：ENTFULL 实证漏拦样本（电视剧·从海底出击/哪吒电影剪辑）
            // mStandardSerialMeta 非空可能是普通视频的空壳（美食/工业美学/央视新闻全被误伤实证），
            // 内层真实内容校验已由上方 dramaShellReal 覆盖，此处不再非空即拦。
            // ColumnMeta 的 mCoverMainTitle/mInnerMainTitle/mDetailTitle 是「电视剧 · xxx」UI 标签文字来源，
            // 读标题文字做关键词判定（非空即拦会误伤普通栏目推荐视频）
            val colM = Reflect.readAny(ent, "mColumnMeta")
            if (colM != null) {
                val colTitle = listOf("mCoverMainTitle", "mInnerMainTitle", "mDetailTitle", "mCoverSubTitle", "mInnerSubTitle", "mCoverDesc")
                    .firstNotNullOfOrNull { f -> Reflect.readString(colM, f)?.takeIf { it.isNotBlank() } }
                if (colTitle != null && (DRAMA_TEXTS.any { colTitle.contains(it) } || colTitle.contains("电影") || colTitle.contains("电视剧") || colTitle.contains("影视"))) {
                    hit("drama:colTitle \"$colTitle\"", qp); return true
                }
            }
            if (DRAMA_TEXTS.any { cap.contains(it) }) { hit("drama:capText", qp); return true }
            if (cap.contains("完整版", true) || cap.contains("整部剧", true) || cap.contains("追剧", true)) { hit("drama:capFull", qp); return true }
            if (cap.contains("电影", true) || cap.contains("电视剧", true)) { hit("drama:capMovie", qp); return true }
        }
        if (Prefs.bool(Prefs.K_FLT_EC, false)) {
            if (cm != null && Reflect.readAny(cm, "mCommodityJumpUrl") != null) return true
            if (Reflect.readAny(ent, "mKwAppMeta") != null) return true
            if (cap.contains("购物") || cap.contains("小黄车")) return true
        }
        if (Prefs.bool(Prefs.K_FLT_LIKE_ON, true)) {
            val th = Prefs.int(Prefs.K_FLT_LIKE_TH, 1000).toLong()
            if (th > 0) {
                val like = pm?.let { Reflect.readLong(it, "mLikeCount") } ?: 0L
                if (like in 1 until th) return true
            }
        }
        val kws = if (Prefs.bool(Prefs.K_FLT_KW_ON, false)) Prefs.str(Prefs.K_FLT_KEYWORDS, "").split(',', '，', ' ').filter { it.isNotBlank() } else emptyList()
        if (kws.isNotEmpty() && cap.isNotBlank() && kws.any { cap.contains(it, true) }) return true
        return false
    }

    private fun shouldFilterContent(qp: Any): Boolean {
        if (!anyFilterOn()) return false
        return decideBySig(contentSigCache, qp) { cachedDecide(contentFilterCache, qp) { decideContentRaw(qp) } }
    }
    // = shouldFilterFeed 去掉点赞阈值规则：列表级移除只按内容类型（广告/AI/直播/短剧/电商/关键词）
    // 点赞命中项保留，避免 adapter 数据列表清空导致 Pager count 失配崩溃
    private fun decideContentRaw(qp: Any): Boolean {
        // ★ 裸实体兜底：同 quickOfficialDirty，无 mEntity 时判 qp 自身类名
        val ent = Reflect.readAny(qp, "mEntity") ?: qp
        val entCls = ent.javaClass.name
        if (!entCls.contains("feed.VideoFeed")) {
            if (Prefs.bool(Prefs.K_FLT_LIVE, false) && entCls.contains("Live", true)) return true
            return false
        }
        val cm = Reflect.readAny(ent, "mCommonMeta")
        val pm = Reflect.readAny(ent, "mPhotoMeta")
        val cap = cm?.let { Reflect.readString(it, "mCaption") } ?: ""
        if (Prefs.bool(Prefs.K_FLT_ADVIDEO, true)) {
            if (Reflect.readAny(ent, "mAd") != null) return true
            val adNovelObj2 = Reflect.readAny(ent, "mAdNovelVideoMeta")
            if (adNovelObj2 != null && (safeNextLong(adNovelObj2, "mNovelId") > 0 || !Reflect.readString(adNovelObj2, "mTitle").isNullOrBlank())) return true
            if (ent.javaClass.name.contains("Ad")) return true
            val adTag = Reflect.readAny(ent, "mAdLabelDescription") ?: Reflect.readAny(ent, "mTitle")
            if (adTag != null && adTag.toString().isNotBlank() && adTag.toString() != "null") return true
        }
        if (Prefs.bool(Prefs.K_FLT_ADS, true)) {
            val capLen = cap.length
            if (capLen <= 30 && AD_TEXTS.any { cap.contains(it, true) }) return true
            if (cap.contains("开屏广告") || cap.contains("广告位") || cap.contains("广告时间") || cap.contains("广告：")) return true
            val un = readUserName(qp, ent)
            if (un.contains("开屏广告") || un.contains("广告") || un.contains("推广") || un.contains("游戏广告")) return true
            // 游戏任务/签到引流广告："极速活跃够开宝箱" "签到+双倍" "60秒升级活跃"
            if (un.contains("活跃") || un.contains("宝箱") || un.contains("极速") || un.contains("签到") || un.contains("开宝箱") || un.contains("金币")) return true
            if (cap.contains("签到") && (cap.contains("活跃") || cap.contains("宝箱") || cap.contains("双倍") || cap.contains("极速") || cap.contains("升级") || cap.contains("开宝箱"))) return true
        }
        if (Prefs.bool(Prefs.K_FLT_LIVE, false)) {
            if (ent.javaClass.name.contains("Live", true)) return true
            val lm = Reflect.readAny(ent, "mLivePlaybackMeta")
            if (lm != null && Reflect.readAny(lm, "mLiveStreamId") != null) return true
            if (pm != null && Reflect.readBool(pm, "mCurrentLivingState") == true) return true
            // 视频广告 + 进入直播间入口（普�?VideoFeed，无 Live 类）：靠入口文案识别
            if (cap.contains("进入直播间") || cap.contains("点击进入直播") || cap.contains("直播中") || cap.contains("提现") || cap.contains("入账") || cap.contains("任务奖励")) return true
            val unLive = readUserName(qp, ent)
            if (unLive.contains("虚拟") || unLive.contains("直播")) return true
        }
        if (Prefs.bool(Prefs.K_FLT_IMAGE, false)) {
            // 正向精准：实体类名 ImageFeed/Atlas（图集数据链路实测）或 mImageModel 存在
            val entClsName = ent.javaClass.name
            if (entClsName.contains("ImageFeed") || entClsName.contains("Atlas")) return true
            if (Reflect.readAny(ent, "mImageModel") != null) return true
            val type = cm?.let { Reflect.readLong(it, "mType") } ?: 0L
            if (type == 2L) return true
            if (pm != null && (Reflect.readBool(pm, "mHasAtlasText") == true || Reflect.readAny(pm, "mAtlasDetailTitle") != null)) return true
            // 兜底：无视频模型 → 图文
            val vm = Reflect.readAny(ent, "mVideoModel")
            if (vm == null || Reflect.readAny(vm, "mVideoUrl") == null) return true
        }
        if (Prefs.bool(Prefs.K_FLT_AI, false)) {
            if (pm != null && Reflect.readBool(pm, "photoAiAnalyze") == true) return true
            if (Reflect.readBool(ent, "mAiTagForAuthor") == true) return true
            val aiTagStr = try { Reflect.readAny(ent, "mAiTagForAuthor")?.toString() } catch (_: Throwable) { null }
            if (aiTagStr != null && aiTagStr.isNotBlank() && aiTagStr != "false" && aiTagStr != "0") return true
            if (cap.contains("ai生成", true) || cap.contains("AI创作") || cap.contains("疑似") || cap.contains("AIGC") || cap.contains("人工智能") || Regex("\\bAI\\b|AI[生成制作绘画]|:AI|AI：").containsMatchIn(cap)) return true
            // ★ 官方作者声明 AI 标记（同 decideFeedRaw 判定）
            if (aiDisclaimerContent(pm) != null) return true
        }
        if (Prefs.bool(Prefs.K_FLT_DRAMA, true)) {
            if (Reflect.readAny(ent, "mKwAppNativeDrama") != null) return true
            if (Reflect.readAny(ent, "mNovelDrama") != null) return true
            if (Reflect.readAny(ent, "mLongToShortDrama") != null) return true
            val adNovelObj3 = Reflect.readAny(ent, "mAdNovelVideoMeta")
            if (adNovelObj3 != null && (safeNextLong(adNovelObj3, "mNovelId") > 0 || !Reflect.readString(adNovelObj3, "mTitle").isNullOrBlank())) return true
            // serialMeta/columnMeta 非空可能是普通视频的空壳（误伤实证：美食/工业美学/央视新闻），
            // 与 decideFeedRaw 对齐：内层真实内容校验 + 标题关键词判定
            if (dramaShellReal(ent)) return true
            val colM2 = Reflect.readAny(ent, "mColumnMeta")
            if (colM2 != null) {
                val colTitle2 = listOf("mCoverMainTitle", "mInnerMainTitle", "mDetailTitle", "mCoverSubTitle", "mInnerSubTitle", "mCoverDesc")
                    .firstNotNullOfOrNull { f -> Reflect.readString(colM2, f)?.takeIf { it.isNotBlank() } }
                if (colTitle2 != null && (DRAMA_TEXTS.any { colTitle2.contains(it) } || colTitle2.contains("电影") || colTitle2.contains("电视剧") || colTitle2.contains("影视"))) return true
            }
            val tube = Reflect.readAny(ent, "mTubeModel")
            if (tube != null && (Reflect.readAny(tube, "mTubeInfo") != null || Reflect.readBool(tube, "mHasTubeTag") == true)) return true
            if (DRAMA_TEXTS.any { cap.contains(it) }) return true
            if (cap.contains("完整版", true) || cap.contains("整部剧", true) || cap.contains("追剧", true)) return true
            if (cap.contains("电影", true) || cap.contains("电视剧", true)) return true
        }
        if (Prefs.bool(Prefs.K_FLT_EC, false)) {
            if (cm != null && Reflect.readAny(cm, "mCommodityJumpUrl") != null) return true
            if (Reflect.readAny(ent, "mKwAppMeta") != null) return true
            if (cap.contains("购物") || cap.contains("小黄车")) return true
        }
        val kws = if (Prefs.bool(Prefs.K_FLT_KW_ON, false)) Prefs.str(Prefs.K_FLT_KEYWORDS, "").split(',', '，', ' ').filter { it.isNotBlank() } else emptyList()
        if (kws.isNotEmpty() && cap.isNotBlank() && kws.any { cap.contains(it, true) }) return true
        return false
    }

    private fun dumpKV(obj: Any?): String {
        if (obj == null) return "null"
        val sb = StringBuilder("{")
        var c: Class<*>? = obj.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 2) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (v != null) {
                        val vs = when (v) {
                            is String -> "\"${v.take(20)}\""
                            is Number, is Boolean -> "$v"
                            is List<*> -> "List(${v.size})"
                            else -> v.javaClass.simpleName
                        }
                        sb.append(f.name).append('=').append(vs).append(';')
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        sb.append('}')
        return sb.toString()
    }

    private fun dumpKVFilter(obj: Any?, kw: String): String {
        if (obj == null) return ""
        val sb = StringBuilder()
        var c: Class<*>? = obj.javaClass
        var lvl = 0
        while (c != null && c != Any::class.java && lvl < 2) {
            for (f in c!!.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (!f.name.contains(kw, true)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (v != null) {
                        val vs = when (v) {
                            is String -> "\"${v.take(20)}\""
                            is Number, is Boolean -> "$v"
                            else -> v.javaClass.simpleName
                        }
                        sb.append(f.name).append('=').append(vs).append(';')
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass; lvl++
        }
        return sb.toString()
    }

    private fun readCaption(qp: Any?): String? {
        if (qp == null) return null
        val ent = Reflect.readAny(qp, "mEntity") ?: return null
        val cm = Reflect.readAny(ent, "mCommonMeta") ?: return null
        return Reflect.readString(cm, "mCaption")
    }

    // ★ 官方作者声明 AI 标记（2026-09-05 AIFULL 实证 + jadx 14.7.40 确认）：
    // mPhotoMeta.mDisclaimergeMessageV2（DisclaimergeMessage）是「作者声明：含AI生成内容」
    // 等官方声明角标的数据源。类字段：content(声明文本)/type/riskStyleType(>0=RUMOR 谣言,
    // 否则 DANGER 危险)/addAiMark。必须匹配 content 含 AI 关键词——不能只判非空
    //（谣言/危险类声明也挂同一对象，非空会误伤）。声明文本为中文短句，contains("AI")
    // 大写敏感安全；ai生成 忽略大小写兜底小写变体。
    private fun aiDisclaimerContent(pm: Any?): String? {
        if (pm == null) return null
        val dis = try { Reflect.readAny(pm, "mDisclaimergeMessageV2") } catch (_: Throwable) { null } ?: return null
        val c = try { Reflect.readAny(dis, "content") as? String } catch (_: Throwable) { null } ?: return null
        if (c.isBlank()) return null
        if (c.contains("AI") || c.contains("ai生成", true) || c.contains("AIGC") || c.contains("人工智能")) return c
        return null
    }

    // ==================== UI 层兜�?====================

    private fun shouldFilterMeta(v: io.github.angbang852.manjiao.data.VideoInfo): Boolean {
        if (Prefs.bool(Prefs.K_FLT_ADS, true) && v.isAd) return true
        if (Prefs.bool(Prefs.K_FLT_LIVE, false) && v.isLive) return true
        if (Prefs.bool(Prefs.K_FLT_AI, false) && (v.isAi || v.caption.orEmpty().contains("ai生成", true) || v.caption.orEmpty().contains("AI创作") || v.caption.orEmpty().contains("疑似") || v.caption.orEmpty().contains("AIGC") || v.caption.orEmpty().contains("人工智能") || Regex("\\bAI\\b|AI[生成制作绘画]|:AI|AI：").containsMatchIn(v.caption.orEmpty()))) return true
        if (Prefs.bool(Prefs.K_FLT_EC, false) && v.isEcommerce) return true
        val kws = if (Prefs.bool(Prefs.K_FLT_KW_ON, false)) Prefs.str(Prefs.K_FLT_KEYWORDS, "").split(',', '，', ' ').filter { it.isNotBlank() } else emptyList()
        if (kws.isNotEmpty()) {
            val cap = v.caption.orEmpty()
            if (cap.isNotBlank() && kws.any { cap.contains(it, true) }) return true
        }
        return false
    }

    // 死代码清理(2026-09-05)：shouldFilterUi/isVideoView/parseCount/skip/findFeedPager/
    // findViewPager/findRv 曾是 UI 层跳过方案的实现，方案被数据层"刷不到"取代后
    // 全部零调用（grep 证实）。

    private fun walk(v: View, cb: (View) -> Unit) {
        cb(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, cb) }
    }
}
