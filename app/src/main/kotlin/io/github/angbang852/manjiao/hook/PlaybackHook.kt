package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.os.Message
import android.view.View
import android.view.ViewGroup
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.data.Prefs
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface

object PlaybackHook {

    @Volatile private var delayedHooked = false
    @Volatile private var lastCompletionMs = 0L
    @Volatile private var currentPlayer: Any? = null
    @Volatile private var checkRunning = false
    @Volatile private var mPauseRef: java.lang.reflect.Method? = null

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        Logger.d("PlaybackHook: hook() called")
        hookLoop(xp, cl)
        hookBgPause(xp, cl)
        Thread {
            while (!delayedHooked) {
                try { Thread.sleep(2000) } catch (_: Throwable) {}
                try { delayedHookAemon(xp, cl) } catch (t: Throwable) { Logger.d("pb delayed fail: ${t.message}") }
            }
        }.start()
    }

    private fun delayedHookAemon(xp: XposedInterface, cl: ClassLoader) {
        val cn = "com.kwai.video.aemonplayer.AemonMediaPlayer"
        val c = Reflect.findClass(cn, cl) ?: try {
            val tcl = Thread.currentThread().contextClassLoader
            if (tcl != null) Class.forName(cn, false, tcl) else null
        } catch (_: Throwable) { null } ?: return
        delayedHooked = true
        Logger.d("PlaybackHook: $cn found methods=${c.declaredMethods.size}")
        val mPause = Reflect.findMethod(c, "pause", 0)
        mPauseRef = mPause
        val mGetPos = Reflect.findMethod(c, "getCurrentPosition", 0)
        val mGetDur = Reflect.findMethod(c, "getDuration", 0)
        val mStart = Reflect.findMethod(c, "start", 0)
        if (mStart != null) {
            xp.hook(mStart).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("pb.aemon.start").intercept { chain ->
                    if (Prefs.bool(Prefs.K_PB_NO_LOOP, false) && System.currentTimeMillis() - lastCompletionMs < 1000) {
                        Logger.d("pb: BLOCK start after completion (noLoop)")
                        return@intercept null
                    }
                    chain.proceed().also {
                        if (Prefs.bool(Prefs.K_PB_NO_LOOP, false) && mGetPos != null && mGetDur != null && mPause != null) {
                            currentPlayer = chain.thisObject
                            if (!checkRunning) startCheckThread(mGetPos, mGetDur, mPause)
                        }
                    }
                }
            Logger.d("PlaybackHook: hooked $cn.start")
        }
        val mSeek = Reflect.findMethod(c, "seekTo", 1)
        if (mSeek != null) {
            xp.hook(mSeek).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("pb.aemon.seekTo").intercept { chain ->
                    val pos = chain.args.getOrNull(0)
                    if (Prefs.bool(Prefs.K_PB_NO_LOOP, false) && pos == 0) {
                        Logger.d("pb: BLOCK seekTo(0) (noLoop)")
                        return@intercept null
                    }
                    chain.proceed()
                }
            Logger.d("PlaybackHook: hooked $cn.seekTo")
        }
        val mComplete = Reflect.findMethod(c, "notifyOnCompletion", 0)
        if (mComplete != null) {
            xp.hook(mComplete).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("pb.aemon.notifyOnCompletion").intercept { chain ->
                    if (Prefs.bool(Prefs.K_PB_NO_LOOP, false)) {
                        lastCompletionMs = System.currentTimeMillis()
                        Logger.d("pb: BLOCK completion (noLoop), pause")
                        try { mPause?.invoke(chain.thisObject) } catch (_: Throwable) {}
                        return@intercept null
                    }
                    chain.proceed()
                }
            Logger.d("PlaybackHook: hooked $cn.notifyOnCompletion")
        }

    }

    private fun startCheckThread(mGetPos: java.lang.reflect.Method, mGetDur: java.lang.reflect.Method, mPause: java.lang.reflect.Method) {
        checkRunning = true
        Thread {
            while (checkRunning) {
                try {
                    Thread.sleep(500)
                    val p = currentPlayer ?: break
                    if (!Prefs.bool(Prefs.K_PB_NO_LOOP, false)) continue
                    val pos = (mGetPos.invoke(p) as? Number)?.toLong() ?: continue
                    val dur = (mGetDur.invoke(p) as? Number)?.toLong() ?: continue

                    if (dur > 1000 && pos >= dur - 300) {
                        mPause.invoke(p)
                        Logger.d("pb: noLoop pause at pos=$pos dur=$dur")
                        break
                    }
                } catch (_: Throwable) {}
            }
            checkRunning = false
        }.also { it.isDaemon = true }.start()
    }

    private fun hookLoop(xp: XposedInterface, cl: ClassLoader) {
        val candidates = arrayOf(
            "com.kwai.player.KwaiRepresentation",
            "com.kwai.video.aemonplayer.AemonMediaPlayer",
            "com.kwai.video.player.AbstractMediaPlayer",
            "com.kwai.video.waynelive.wayneplayer.WayneLivePlayer",
            "android.media.MediaPlayer"
        )
        val methods = arrayOf("setLooping", "setLoop", "setRepeatMode", "setCycle", "setAutoReplay", "setAutoLoop", "setReplay")
        var hooked = false
        for (cn in candidates) {
            val c = Reflect.findClass(cn, cl) ?: continue
            val loopMethods = c.declaredMethods.filter { it.name.contains("loop", true) || it.name.contains("repeat", true) || it.name.contains("replay", true) || it.name.contains("cycle", true) }
            if (loopMethods.isNotEmpty()) Logger.d("PlaybackHook: $cn loopMethods=${loopMethods.map { it.name + "(" + it.parameterTypes.size + ")" }}")
            for (mn in methods) {
                val m = Reflect.findMethod(c, mn, 1)
                if (m != null) {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("pb.loop.$cn.$mn").intercept { chain ->
                            if (Prefs.bool(Prefs.K_PB_NO_LOOP, false)) {
                                Logger.d("pb: force no-loop $mn orig=${chain.args[0]}")
                                chain.args[0] = if (mn == "setRepeatMode") 0 else false
                            }
                            chain.proceed()
                        }
                    Logger.d("PlaybackHook: hooked $cn.$mn")
                    hooked = true
                }
            }
            if (hooked) break
        }
        if (!hooked) Logger.d("PlaybackHook: no looping method found")

    }

    private fun hookBgPause(xp: XposedInterface, cl: ClassLoader) {
        val targets = mutableSetOf(
            KsClass.PHOTO_DETAIL_ACTIVITY,
            KsClass.PHOTO_DETAIL_ACTIVITY_TABLET,
            "com.yxcorp.gifshow.HomeActivity"
        )
        for (a in targets) {
            val c = Reflect.findClass(a, cl) ?: continue
            for (mn in arrayOf("onPause", "onStop")) {
                val m = Reflect.findMethod(c, mn, 0) ?: continue
                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("pb.bg.$a.$mn").intercept { chain ->
                        chain.proceed()
                        try {
                            if (Prefs.bool(Prefs.K_PB_BG_PAUSE, false)) {
                                pausePlayer(chain.thisObject as? Activity)
                            }
                        } catch (_: Throwable) {}
                        null
                    }
                Logger.d("PlaybackHook: hooked $mn $a")
            }
        }
    }

    private fun pausePlayer(act: Activity?) {
        if (act == null) return
        try {
            val p = currentPlayer ?: return
            val mPause = mPauseRef ?: return
            mPause.invoke(p)
            Logger.d("pb: bg paused player")
        } catch (t: Throwable) { Logger.d("pb pause fail: ${t.message}") }
    }

    private fun findPlayer(root: View): Any? {
        if (root.javaClass.name.contains("KwaiRepresentation") || root.javaClass.name.contains("VideoPlayer")) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i) ?: continue
                val p = findPlayer(child)
                if (p != null) return p
            }
        }
        return null
    }
}
