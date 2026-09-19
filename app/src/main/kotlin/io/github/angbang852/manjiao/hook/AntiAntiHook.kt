package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.io.IOException

object AntiAntiHook {
    private val HOOK_PKGS = setOf(
        "de.robv.android.xposed.installer", "de.robv.android.xposed.installer_33",
        "org.lsposed.manager", "com.saurik.substrate", "com.android.cydia",
        "re.frida.server", "com.topjohnwu.magisk", "io.github.lsposed", "io.github.angbang852.manjiao"
    )
    private val SU_PATHS = setOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/bin/su",
        "/su/bin/su", "/vendor/bin/su", "/data/local/xbin/su"
    )

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        // ★ 接通「绕过环境检测」开关：K_ANTI 此前定义后无任何消费点（死开关，
        // UI 拨了也没用）；默认 true 保持既有行为
        if (!Prefs.bool(Prefs.K_ANTI, true)) { Logger.d("AntiAnti disabled by config"); return }
        hookPm(xp, cl); hookFile(xp); hookExec(xp); hookDebug(xp); hookProp(xp)
        Logger.d("AntiAnti installed")
    }

    private fun hookPm(xp: XposedInterface, cl: ClassLoader) {
        val pm = Reflect.findClass("android.app.ApplicationPackageManager", cl) ?: return
        val m = Reflect.findMethod(pm, "getPackageInfo", 2) ?: return
        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("anti.pm").intercept { chain ->
            val n = chain.args[0] as? String
            if (n != null && n in HOOK_PKGS) {
                // ★ PROTECTIVE 模式下回调异常被框架吞掉（throw 根本到不了宿主），
                // 反检测静默失效。改为直接返回伪造空 PackageInfo，不依赖异常语义
                Logger.d("anti.pm fake for $n")
                return@intercept android.content.pm.PackageInfo().apply { packageName = n }
            }
            chain.proceed()
        }
        Logger.safe("pm2") {
            val m2 = Reflect.findMethod(pm, "getPackageInfoAsUser", 3) ?: return@safe
            xp.hook(m2).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("anti.pm2").intercept { chain ->
                val n = chain.args[0] as? String
                if (n != null && n in HOOK_PKGS) {
                    Logger.d("anti.pm2 fake for $n")
                    return@intercept android.content.pm.PackageInfo().apply { packageName = n }
                }
                chain.proceed()
            }
        }
    }

    private fun hookFile(xp: XposedInterface) {
        val m = File::class.java.getDeclaredMethod("exists")
        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("anti.file").intercept { chain ->
            // ★ 热路径税：宿主所有 File.exists() 都进回调。先用免分配的 path 粗筛
            //（不解析路径、零分配），仅可能命中时才做 absolutePath 精确比对
            val f = chain.thisObject as File
            val p = f.path
            if (!p.contains("su") && !p.contains("magisk")) return@intercept chain.proceed()
            if (f.absolutePath in SU_PATHS) false else chain.proceed()
        }
    }

    private fun hookExec(xp: XposedInterface) {
        for (m in Runtime::class.java.declaredMethods) {
            if (m.name != "exec") continue
            Logger.safe("anti.exec.${m.parameterTypes.size}") {
                // PASSTHROUGH：拒绝逻辑依赖异常传播到宿主（PROTECTIVE 会吞掉它）。
                // 匹配收窄：原 contains("su") 会误伤含 "su" 子串的正常命令
                //（measure/summary 等），改为 su 词形边界
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).setId("anti.exec").intercept { chain ->
                    val c = chain.args.firstOrNull()?.toString() ?: ""
                    if (c.startsWith("su") || c.contains("/su") || c.contains(" su") || c.contains("magisk")) throw IOException("denied")
                    chain.proceed()
                }
            }
        }
    }

    private fun hookDebug(xp: XposedInterface) {
        Logger.safe("dbg") {
            val c = Class.forName("android.os.Debug")
            val m = c.getDeclaredMethod("isDebuggerConnected")
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("anti.dbg").intercept { false }
        }
    }

    private fun hookProp(xp: XposedInterface) {
        val m = System::class.java.getDeclaredMethod("getProperty", String::class.java)
        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("anti.prop").intercept { chain ->
            val k = chain.args[0] as? String
            if (k == "ro.kernel.qemu" || k == "qemu.hw.mainkeys") null else chain.proceed()
        }
        Logger.safe("sysprop") {
            val c = Class.forName("android.os.SystemProperties")
            val m2 = c.getDeclaredMethod("get", String::class.java)
            xp.hook(m2).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("anti.sysprop").intercept { chain ->
                val k = chain.args[0] as? String ?: ""
                if (k.contains("genymotion") || k.contains("qemu")) "" else chain.proceed()
            }
        }
    }
}
