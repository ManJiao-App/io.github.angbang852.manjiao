package io.github.angbang852.manjiao.util

import android.util.Log
import io.github.libxposed.api.XposedModule

object Logger {
    private const val TAG = "SlowKick"
    private var mod: XposedModule? = null
    // 性能优化-日志静默：刷屏级诊断日志（feed diag/VIEWDIAG 等）每条都是主线程
    // 字符串拼接 + JNI 写 logcat，快手滑动时每秒几十条是实打实的开销
    @Volatile var quiet: Boolean = false

    fun init(m: XposedModule) { mod = m }

    fun d(msg: String) { if (quiet) return; try { mod?.log(Log.INFO, TAG, msg) } catch (_: Throwable) {}; try { Log.d(TAG, msg) } catch (_: Throwable) {} }
    // 关键诊断/生命周期日志：不受 quiet 静默影响（频率极低，无性能开销）
    fun always(msg: String) { try { mod?.log(Log.INFO, TAG, msg) } catch (_: Throwable) {}; try { Log.d(TAG, msg) } catch (_: Throwable) {} }
    fun d(t: Throwable) { try { mod?.log(Log.ERROR, TAG, "", t) } catch (_: Throwable) {} }
    fun d(msg: String, t: Throwable) { d(msg); d(t) }

    inline fun safe(tag: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { d("$tag: ${t.javaClass.simpleName}: ${t.message}") }
    }
}
