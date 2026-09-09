package io.github.angbang852.manjiao.hook

import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.io.IOException

object AntiAntiHook {
    private val HOOK_PKGS = setOf(
        "de.robv.android.xposed.installer", "de.robv.android.xposed.installer_33",
        "org.lsposed.manager", "com.saurik.substrate", "com.android.cydia",
        "re.frida.server", "com.topjohnwu.magisk", "io.github.lsposed"
    )
    private val SU_PATHS = setOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/bin/su",
        "/su/bin/su", "/vendor/bin/su", "/data/local/xbin/su"
    )

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        hookPm(xp, cl); hookFile(xp); hookExec(xp); hookDebug(xp); hookProp(xp)
        Logger.d("AntiAnti installed")
    }

    private fun hookPm(xp: XposedInterface, cl: ClassLoader) {
        val pm = Reflect.findClass("android.app.ApplicationPackageManager", cl) ?: return
        val nf = Reflect.findClass("android.content.pm.PackageManager\$NameNotFoundException", cl) ?: return
        val m = Reflect.findMethod(pm, "getPackageInfo", 2) ?: return
        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("anti.pm").intercept { chain ->
            val n = chain.args[0] as? String
            if (n != null && n in HOOK_PKGS) {
                throw nf.getDeclaredConstructor(String::class.java).newInstance(n) as Throwable
            }
            chain.proceed()
        }
        Logger.safe("pm2") {
            val m2 = Reflect.findMethod(pm, "getPackageInfoAsUser", 3) ?: return@safe
            xp.hook(m2).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("anti.pm2").intercept { chain ->
                val n = chain.args[0] as? String
                if (n != null && n in HOOK_PKGS) {
                    throw nf.getDeclaredConstructor(String::class.java).newInstance(n) as Throwable
                }
                chain.proceed()
            }
        }
    }

    private fun hookFile(xp: XposedInterface) {
        val m = File::class.java.getDeclaredMethod("exists")
        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("anti.file").intercept { chain ->
            if ((chain.thisObject as File).absolutePath in SU_PATHS) false else chain.proceed()
        }
    }

    private fun hookExec(xp: XposedInterface) {
        for (m in Runtime::class.java.declaredMethods) {
            if (m.name != "exec") continue
            Logger.safe("anti.exec.${m.parameterTypes.size}") {
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("anti.exec").intercept { chain ->
                    val c = chain.args.firstOrNull()?.toString() ?: ""
                    if (c.contains("su") || c.contains("magisk")) throw IOException("denied")
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
