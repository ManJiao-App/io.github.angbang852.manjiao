package io.github.angbang852.manjiao.hook

import android.app.Service
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.api.XposedInterface

object PurifyHook {
    private const val PUSH_SERVICE = "com.yxcorp.gifshow.pushv3bridge.MatrixPushV3Service"
    private const val WEBVIEW_SERVICE = "com.kuaishou.webkit.process.SandboxedProcessService0"
    private const val LOG_PROVIDER = "com.yxcorp.gifshow.log.service.ConanLogContentProvider"

    private var blockDiag = 0

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        hookServiceCreate(xp)
        hookLogProvider(xp, cl)
        Logger.d("PurifyHook installed")
    }

    private fun hookServiceCreate(xp: XposedInterface) {
        try {
            val m = Service::class.java.getDeclaredMethod("onCreate")
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("purify.svc").intercept { chain ->
                val r = chain.proceed()
                try {
                    val svc = chain.thisObject as? Service
                    if (svc != null) {
                        val cn = svc.javaClass.name
                        val block = when {
                            Prefs.bool(Prefs.K_PURIFY_PUSH, true) && cn == PUSH_SERVICE -> "push"
                            Prefs.bool(Prefs.K_PURIFY_WEBVIEW, true) && cn == WEBVIEW_SERVICE -> "webview"
                            else -> null
                        }
                        if (block != null) {
                            if (blockDiag < 30) { blockDiag++; Logger.always("purify stop service: $block ($cn)") }
                            svc.stopSelf()
                        }
                    }
                } catch (_: Throwable) {}
                r
            }
        } catch (_: Throwable) {}
    }

    private fun hookLogProvider(xp: XposedInterface, cl: ClassLoader) {
        try {
            val cls = try { Class.forName(LOG_PROVIDER, false, cl) } catch (_: Throwable) { null } ?: return
            for (m in cls.declaredMethods) {
                val mn = m.name
                if (mn != "query" && mn != "insert" && mn != "update" && mn != "delete" && mn != "call") continue
                try {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("purify.log.$mn").intercept { chain ->
                        if (Prefs.bool(Prefs.K_PURIFY_LOG, true)) {
                            if (blockDiag < 30) { blockDiag++; Logger.always("purify block log: $mn") }
                            return@intercept null
                        }
                        chain.proceed()
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}