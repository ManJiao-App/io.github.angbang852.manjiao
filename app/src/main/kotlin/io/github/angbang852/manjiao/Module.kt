package io.github.angbang852.manjiao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.hook.AntiAntiHook
import io.github.angbang852.manjiao.hook.ContentFilterHook
import io.github.angbang852.manjiao.hook.GestureHook
import io.github.angbang852.manjiao.hook.GoldFloatHook
import io.github.angbang852.manjiao.hook.ImmersiveHook
import io.github.angbang852.manjiao.hook.PerfHook
import io.github.angbang852.manjiao.hook.PlaybackHook
import io.github.angbang852.manjiao.hook.PurifyHook

import io.github.angbang852.manjiao.hook.SharePanelHook
import io.github.angbang852.manjiao.hook.VideoDownloaderHook
import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

class Module : XposedModule {
    private val init = AtomicBoolean(false)
    // ★ 性能优化-进程级一次初始化：快手插件化框架（nebula 等）会在主进程多个线程
    // 反复 new Application 并调 onCreate——不幂等的话 receiver 会重复注册十几遍、
    // 全部 hook 重复装载（ANR 根因之一，实测单次启动重复 13 次）
    private val onCreateDone = AtomicBoolean(false)

    constructor() { }

    // ★ libxposed 的 ModuleLoadedParam.processName 是权威进程名（实测三进程各自正确）；
    // 而 ctx.applicationInfo.processName 在快手子进程里错误地返回主包名（不带 :messagesdk），
    // 导致"仅主进程注入"判定失效——子进程照样全量注入（本次实测抓到）
    private var realProcName: String? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Logger.init(this)
        realProcName = param.processName
        Logger.d("onModuleLoaded process=" + param.processName)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        if (pkg != KsClass.PKG && pkg != KsClass.PKG_NEBULA) return
        if (!init.compareAndSet(false, true)) return

        Logger.d("onPackageLoaded $pkg")
        val cl = try { param.defaultClassLoader } catch (_: Throwable) { null }
        if (cl == null) { Logger.d("no classloader"); return }

        val appCls = Class.forName("android.app.Application", false, cl)
        hook(appCls.getDeclaredMethod("onCreate"))
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("app.onCreate")
            .intercept { chain ->
                chain.proceed()
                try {
                    val ctx = chain.thisObject as Context
                    Prefs.init(ctx)
                    // 性能优化-日志静默：刷屏级诊断日志的字符串拼接+logcat 写入都在调用线程
                    Logger.quiet = Prefs.bool(Prefs.K_PERF_QUIET, true)
                    // ★ 性能优化-仅主进程注入：快手的 push_v3/messagesdk/kwv_sandboxed 等子进程
                    // 不装任何 hook、不注册 receiver、不发 query——子进程注入只会带来
                    // 启动变慢 + 内存浪费 + 广播风暴 + 主线程阻塞（ANR 根因之一）
                    // 进程名优先取 onModuleLoaded 的权威值；ctx.applicationInfo.processName
                    // 在快手子进程实测错误返回主包名（不带 :messagesdk），不可作判定依据
                    val procName = realProcName
                        ?: try { ctx.applicationInfo.processName } catch (_: Throwable) { null }
                    Logger.always("proc=$procName mainproc=" + Prefs.bool(Prefs.K_PERF_MAINPROC, true))
                    if (Prefs.bool(Prefs.K_PERF_MAINPROC, true) && procName != null && procName.contains(':')) {
                        Logger.always("skip sub process: $procName")
                        // 子进程仍装载 PurifyHook：推送服务/WebView 沙盒运行在各自子进程，
                        // 需在此进程内 hook Service.onCreate 才能 stopSelf 拦截
                        Logger.safe("purify") { PurifyHook.hook(this, cl) }
                        return@intercept null
                    }
                    if (!onCreateDone.compareAndSet(false, true)) {
                        Logger.always("onCreate repeat, skip re-init")
                        return@intercept null
                    }
                    Logger.d("prefs ready")
                    ctx.registerReceiver(object : BroadcastReceiver() {
                        override fun onReceive(c: Context, i: Intent) {
                            try {
                                if (i.action == Prefs.ACTION_PULL) {
                                    try { Prefs.replyPull(c) } catch (_: Throwable) {}
                                    return
                                }
                                val type = i.getStringExtra("type") ?: return
                                val key = i.getStringExtra("key") ?: return
                                when (type) {
                                    "bool" -> Prefs.applyRemote(key, i.getBooleanExtra("value", false))
                                    "strset" -> {
                                        val arr = try { i.getStringArrayExtra("value") } catch (_: Throwable) { null }
                                        Prefs.applyRemote(key, (arr ?: emptyArray()).toSet())
                                    }
                                    "int" -> Prefs.applyRemote(key, i.getIntExtra("value", 0))
                                    "str" -> Prefs.applyRemote(key, i.getStringExtra("value") ?: "")
                                }
                                // 配置变化 → 过滤判定缓存失效（下次判定走全量反射并重新缓存）
                                if (key.startsWith("flt_") || key.startsWith("perf_")) ContentFilterHook.invalidateFilterCache()
                                Logger.quiet = Prefs.bool(Prefs.K_PERF_QUIET, true)
                                Logger.d("prefs sync $type $key")
                            } catch (t: Throwable) { Logger.d("prefs recv fail: ${t.message}") }
                        }
                    }, IntentFilter(Prefs.ACTION_UPDATE).apply { addAction(Prefs.ACTION_PULL) }, Context.RECEIVER_EXPORTED)
                    Logger.d("prefs receiver registered")
                    Logger.safe("anti") { AntiAntiHook.hook(this, cl) }
                    Logger.safe("dl") { VideoDownloaderHook.hook(this, cl) }
                    Logger.safe("share") { SharePanelHook.hook(this, cl) }
                    Logger.safe("imm") { ImmersiveHook.hook(this, cl) }
                    Logger.safe("gold") { GoldFloatHook.hook(this, cl) }
                    Logger.safe("flt") { ContentFilterHook.hook(this, cl) }
                    Logger.safe("gs") { GestureHook.hook(this, cl) }
                    Logger.safe("pb") { PlaybackHook.hook(this, cl) }

                    Logger.safe("perf") { PerfHook.hook(this, cl) }
                    Logger.safe("purify") { PurifyHook.hook(this, cl) }
                } catch (t: Throwable) { Logger.d("onCreate hook: $t") }
                null
            }
    }
}
