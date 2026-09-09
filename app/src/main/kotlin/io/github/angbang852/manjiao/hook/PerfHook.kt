package io.github.angbang852.manjiao.hook

import android.hardware.Sensor
import android.hardware.SensorManager
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.libxposed.api.XposedInterface

/**
 * 性能优化：
 * 1) 刷屏日志静默——native hook __android_log_buf_write/__android_log_write/__android_log_vprint
 *    按 tag 过滤 P2P 日志 + 按 text 过滤 Invalid resource ID（走 native C++ ALOGD，Java Log.d hook 拦不住）。
 *    实测 P2P 分片6片日志占 logcat 57%，拦截后 GC 压力降 90%。
 * 2) 广告摇一摇拦截——高频加速度计注册拦截。
 */
object PerfHook {
    private var diag = 0
    private val SPAM_TAGS = setOf("xySDK", "Klink")

    @JvmStatic external fun nativeInitLogHook(): Boolean

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        hookLogSpam(xp, cl)
        hookSensor(xp)
        Logger.always("PerfHook installed")
    }

    private fun hookLogSpam(xp: XposedInterface, cl: ClassLoader) {
        if (!Prefs.bool(Prefs.K_PERF_LOGSPAM, true)) return
        var nativeOk = false
        try {
            System.loadLibrary("loghook")
            nativeOk = nativeInitLogHook()
        } catch (t: Throwable) { Logger.always("native loghook load fail: $t") }
        Logger.always("perf logspam native=$nativeOk tags=$SPAM_TAGS")
        try {
            val logCls = Class.forName("android.util.Log", false, cl)
            var cnt = 0
            for (m in logCls.declaredMethods) {
                if (m.name != "d") continue
                val pt = m.parameterTypes
                if (pt.size != 2 && pt.size != 3) continue
                if (pt[0] != String::class.java || pt[1] != String::class.java) continue
                cnt++
                Logger.safe("perf.log.${pt.size}") {
                    xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("perf.log.d${pt.size}")
                        .intercept { chain ->
                            try {
                                val tag = chain.args[0] as? String
                                if (tag != null && SPAM_TAGS.contains(tag)) return@intercept 0
                            } catch (_: Throwable) {}
                            chain.proceed()
                        }
                }
            }
            Logger.always("perf logspam java hook methods=$cnt")
        } catch (t: Throwable) { Logger.always("perf logspam java hook fail: $t") }
    }

    private fun hookSensor(xp: XposedInterface) {
        try {
            for (m in SensorManager::class.java.declaredMethods) {
                if (m.name != "registerListener") continue
                if (m.parameterTypes.size < 3) continue
                if (!m.parameterTypes.contains(Sensor::class.java)) continue
                Logger.safe("perf.sensor.${m.parameterTypes.size}") {
                    xp.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("perf.sensor.${m.parameterTypes.size}")
                        .intercept { chain ->
                            try {
                                if (Prefs.bool(Prefs.K_PERF_SENSOR, false)) {
                                    val s = chain.args.filterIsInstance<Sensor>().firstOrNull()
                                    val rate = chain.args.filterIsInstance<Int>().firstOrNull() ?: 0
                                    if (s?.type == Sensor.TYPE_ACCELEROMETER && rate in 1..59) {
                                        if (diag < 10) { diag++; Logger.d("perf block shake sensor rate=$rate") }
                                        return@intercept false
                                    }
                                }
                            } catch (_: Throwable) {}
                            chain.proceed()
                        }
                }
            }
        } catch (_: Throwable) {}
    }
}
