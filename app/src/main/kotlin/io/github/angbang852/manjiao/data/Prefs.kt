package io.github.angbang852.manjiao.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.angbang852.manjiao.util.Logger
import java.io.File
import java.util.Properties

object Prefs {
    const val FILE = "slowkick"
    const val ACTION_UPDATE = "io.github.angbang852.manjiao.PREFS_UPDATE"
    const val ACTION_WRITE = "io.github.angbang852.manjiao.PREFS_WRITE"
    const val ACTION_QUERY = "io.github.angbang852.manjiao.PREFS_QUERY"
    const val ACTION_PULL = "io.github.angbang852.manjiao.PREFS_PULL"
    private const val OWN_PKG = "io.github.angbang852.manjiao"
    private const val MEDIA_DIR = "/sdcard/Android/media/io.github.angbang852.manjiao"
    private const val MEDIA_FILE = "$MEDIA_DIR/slowkick.properties"
    private const val PULL_INTERVAL_MS = 2000L
    private const val QUERY_INTERVAL_MS = 30000L
    private const val QUERY_INTERVAL_SLOW = 300000L
    // 快手进程内的持久化文件（模块代码跑在快手进程，写快手私有目录不受 FUSE 限制；
    // 冷启动恢复上次配置，不再依赖模块 app 进程可达——模块 app 在部分 ROM 上
    // 被广播拉不活，ACTION_QUERY 链路不可靠）
    private const val REMOTE_SP = "slowkick_remote"

    const val K_ANTI = "anti_detect"
    const val K_DL_PATH = "dl_path"
    const val DEFAULT_PATH = "/sdcard/Download"

    const val K_IMM_ON = "imm_one_click"
    const val K_IMM_CUSTOM = "imm_custom"
    const val K_IMM_HIDE = "imm_hide_items"
    const val K_IMM_TOPBAR_ON = "imm_topbar_on"
    const val K_IMM_TOPBAR = "imm_topbar_items"
    const val K_IMM_RIGHT_ON = "imm_right_on"
    const val K_IMM_RIGHT_ITEMS = "imm_right_items"
    const val K_IMM_NICKNAME = "imm_nickname"
    const val K_IMM_COLLECTION = "imm_collection"
    const val K_IMM_BOTTOM_BAR = "imm_bottom_bar"
    const val K_IMM_GOLD = "imm_gold"


    const val K_FLT_ADS = "flt_ads"
    const val K_FLT_ADVIDEO = "flt_advideo"
    const val K_FLT_DRAMA = "flt_drama"
    const val K_FLT_IMAGE = "flt_image"
    const val K_FLT_LIVE = "flt_live"
    const val K_FLT_AI = "flt_ai"
    const val K_FLT_EC = "flt_ec"
    const val K_FLT_LIKE_ON = "flt_like_on"
    const val K_FLT_LIKE_TH = "flt_like_th"
    const val K_FLT_KEYWORDS = "flt_keywords"
    const val K_FLT_KW_ON = "flt_kw_on"
    const val K_FLT_NOMORE = "flt_nomore"


    // 性能优化
    const val K_PERF_MAINPROC = "perf_mainproc"
    const val K_PERF_FCACHE = "perf_fcache"
    const val K_PERF_LOWFREQ = "perf_lowfreq"
    const val K_PERF_QUIET = "perf_quiet"
    const val K_PERF_SENSOR = "perf_sensor"
    const val K_PERF_LOGSPAM = "perf_logspam"

    // 手势功能
    const val K_GS_NO_DBL_LIKE = "gs_no_dbl_like"
    const val K_GS_OPEN_COMMENT = "gs_open_comment"
    const val K_GS_OPEN_COMMENT_TAPS = "gs_open_comment_taps"
    const val K_GS_OPEN_MENU = "gs_open_menu"
    const val K_GS_OPEN_MENU_TAPS = "gs_open_menu_taps"

    // 播放控制
    const val K_PB_NO_LOOP = "pb_no_loop"
    const val K_PB_BG_PAUSE = "pb_bg_pause"

    // 快手净化
    const val K_PURIFY_PUSH = "purify_push"
    const val K_PURIFY_LOG = "purify_log"
    const val K_PURIFY_WEBVIEW = "purify_webview"

    @Volatile private var sp: SharedPreferences? = null
    @Volatile private var remote = false
    @Volatile private var inited = false
    @Volatile private var cache: MutableMap<String, Any>? = null
    @Volatile private var lastPull = 0L
    @Volatile private var lastQuery = 0L
    @Volatile private var appCtx: Context? = null
    // 快手进程内的持久化 SP（remote 模式专用）
    @Volatile private var rsp: SharedPreferences? = null
    // media 文件在此设备被 FUSE 权限封死（EACCES）后置位：不再反复读文件（每次都抛异常，
    // 每 2 秒一次的失败 IO 是卡顿源），配置同步完全走广播链路
    @Volatile private var mediaDead = false

    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            appCtx = ctx.applicationContext
            remote = ctx.packageName != OWN_PKG
            if (remote) {
                rsp = ctx.getSharedPreferences(REMOTE_SP, Context.MODE_PRIVATE)
                pullRemote(force = true)
                // media 不可达时用快手本地 SP 恢复上次配置（用户在快手菜单里改过的开关
                // 重启后不再丢失）
                if (cache == null || cache!!.isEmpty()) {
                    val m = HashMap<String, Any>()
                    for ((k, v) in rsp?.all ?: emptyMap<String, Any>()) if (v != null) m[k] = v
                    // 2026-09-05 修复：此前此处 remove 了 K_IMM_ON/K_IMM_CUSTOM（"危险开关
                    // 防残留"），导致每次快手重启一键沉浸/自定义隐藏被强制关闭——用户在
                    // 模块菜单开的开关跨重启全部失效（imm_custom 是自定义隐藏总闸，被抹
                    // 后顶栏/右侧/底栏/昵称/金币/合集全部连带失效）。rsp 是菜单开关真实
                    // 写入的持久状态，恢复它才是正确语义；关闭开关同样会写 false 落盘。
                    if (m.isNotEmpty()) {
                        cache = m
                        Logger.d("prefs restore from local sp keys=${m.size}")
                    }
                }
            } else {
                sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                mergeMediaIntoSp()
                writeMediaSnapshot()
            }
            inited = true
            Logger.d("prefs init remote=$remote keys=${cache?.size ?: sp?.all?.size ?: 0}")
        }
    }

    fun initLocal(ctx: Context) {
        remote = false
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun pullRemote(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPull < PULL_INTERVAL_MS) return
        if (mediaDead) {
            // 文件路已死：只保留低频 query 通道（30 秒限频），不再碰文件 IO
            if (cache == null) { cache = HashMap(); maybeSendQuery() } else maybeSendQuery()
            lastPull = now
            return
        }
        try {
            val f = File(MEDIA_FILE)
            if (!f.exists()) { if (cache == null) cache = HashMap(); maybeSendQuery(); return }
            val props = Properties()
            f.inputStream().use { props.load(it) }
            val m = HashMap<String, Any>()
            for (k in props.stringPropertyNames()) {
                val v = props.getProperty(k) ?: continue
                when {
                    v == "true" || v == "false" -> m[k] = v.toBoolean()
                    v.startsWith("int:") -> v.substring(4).toIntOrNull()?.let { m[k] = it }
                    v.startsWith("set:") -> m[k] = v.substring(4).split(",").filter { it.isNotEmpty() }.toSet()
                    else -> m[k] = v
                }
            }
            cache = m
            lastPull = now
        } catch (t: Throwable) {
            if (t is java.io.FileNotFoundException || t.cause is java.io.FileNotFoundException ||
                (t.message?.contains("EACCES") == true) || (t.message?.contains("Permission denied") == true)
            ) {
                // 权限封死：这台设备上 media 路彻底不可用，永久退避
                mediaDead = true
                Logger.d("prefs media dead (EACCES), switch to broadcast-only")
            } else {
                Logger.d("prefs media read fail: $t")
            }
            if (cache == null) cache = HashMap()
            maybeSendQuery()
            lastPull = now
        }
    }

    private fun maybeSendQuery() {
        val c = appCtx ?: return
        val now = System.currentTimeMillis()
        // 性能优化-低频同步：默认 5 分钟一次。30 秒周期在多进程下是广播风暴
        // （N 进程 × 每进程一条 query × 模块 app 回发全部键 × 全部进程再各收一遍）
        val interval = if (cache?.get(K_PERF_LOWFREQ) == false) QUERY_INTERVAL_MS else QUERY_INTERVAL_SLOW
        if (now - lastQuery < interval) return
        lastQuery = now
        try {
            // FLAG_INCLUDE_STOPPED_PACKAGES：模块 app 刚被 adb install / force-stop 后处于
            // stopped state，不加此 flag 静态 receiver 收不到广播（配置同步全断的根因）
            c.sendBroadcast(
                Intent(ACTION_QUERY).setPackage(OWN_PKG)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            )
            Logger.d("prefs query sent")
        } catch (_: Throwable) {}
    }

    fun broadcastAll(ctx: Context) {
        val s = sp ?: return
        val all = s.all
        for ((k, v) in all) {
            when (v) {
                is Boolean -> sendUpdateBroadcast(ctx, "bool", k, v)
                is Int -> sendUpdateBroadcast(ctx, "int", k, v)
                is Set<*> -> sendUpdateBroadcast(ctx, "strset", k, v)
                is String -> sendUpdateBroadcast(ctx, "str", k, v)
            }
        }
        Logger.d("prefs broadcastAll keys=${all.size}")
    }

    private fun ensureMediaDir() {
        try {
            val dir = File(MEDIA_DIR)
            if (!dir.exists()) dir.mkdirs()
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
        } catch (_: Throwable) {}
    }

    private fun relaxMediaFilePerm(f: File) {
        try { f.setReadable(true, false); f.setWritable(true, false) } catch (_: Throwable) {}
    }

    private fun mergeMediaIntoSp() {
        try {
            val s = sp ?: return
            val f = File(MEDIA_FILE)
            if (!f.exists()) return
            val props = Properties()
            f.inputStream().use { props.load(it) }
            val e = s.edit()
            for (k in props.stringPropertyNames()) {
                val v = props.getProperty(k) ?: continue
                when {
                    v == "true" || v == "false" -> e.putBoolean(k, v.toBoolean())
                    v.startsWith("int:") -> v.substring(4).toIntOrNull()?.let { e.putInt(k, it) }
                    v.startsWith("set:") -> e.putStringSet(k, v.substring(4).split(",").filter { it.isNotEmpty() }.toSet())
                    else -> e.putString(k, v)
                }
            }
            e.apply()
        } catch (_: Throwable) {}
    }

    private fun writeMediaSnapshot() {
        try {
            val s = sp ?: return
            ensureMediaDir()
            val props = Properties()
            for ((k, v) in s.all) {
                when (v) {
                    is Boolean -> props[k] = v.toString()
                    is Int -> props[k] = "int:$v"
                    is Set<*> -> props[k] = "set:" + (v as Set<String>).joinToString(",")
                    else -> props[k] = v.toString()
                }
            }
            val f = File(MEDIA_FILE)
            f.delete()
            f.outputStream().use { props.store(it, null) }
            relaxMediaFilePerm(f)
        } catch (t: Throwable) { Logger.d("prefs media write fail: $t") }
    }

    fun reload() {
        if (remote) pullRemote(force = true)
        else mergeMediaIntoSp()
    }

    private fun remoteWriteMedia(type: String, key: String, value: Any?) {
        try {
            ensureMediaDir()
            val f = File(MEDIA_FILE)
            val props = Properties()
            if (f.exists()) f.inputStream().use { props.load(it) }
            when (type) {
                "bool" -> props[key] = value.toString()
                "int" -> props[key] = "int:$value"
                "str" -> props[key] = value.toString()
                "strset" -> props[key] = "set:" + (value as? Set<*> ?: emptySet<String>()).joinToString(",")
            }
            f.outputStream().use { props.store(it, null) }
            relaxMediaFilePerm(f)
            lastPull = System.currentTimeMillis()
        } catch (t: Throwable) { Logger.d("prefs remote media write fail: $t") }
    }

    fun bool(key: String, def: Boolean): Boolean {
        if (remote) { pullRemote(false); return cache?.get(key) as? Boolean ?: def }
        return sp?.getBoolean(key, def) ?: def
    }
    fun str(key: String, def: String): String {
        if (remote) { pullRemote(false); return cache?.get(key) as? String ?: def }
        return sp?.getString(key, def) ?: def
    }
    fun int(key: String, def: Int): Int {
        if (remote) { pullRemote(false); return cache?.get(key) as? Int ?: def }
        return sp?.getInt(key, def) ?: def
    }
    fun strSet(key: String): Set<String> {
        if (remote) { pullRemote(false); return cache?.get(key) as? Set<String> ?: emptySet() }
        return sp?.getStringSet(key, emptySet()) ?: emptySet()
    }

    private fun ed(): SharedPreferences.Editor? = sp?.edit()

    fun setBool(key: String, v: Boolean) { ed()?.putBoolean(key, v)?.apply(); if (!remote) writeMediaSnapshot() else cache?.put(key, v) }
    fun setStr(key: String, v: String) { ed()?.putString(key, v)?.apply(); if (!remote) writeMediaSnapshot() else cache?.put(key, v) }
    fun setInt(key: String, v: Int) { ed()?.putInt(key, v)?.apply(); if (!remote) writeMediaSnapshot() else cache?.put(key, v) }
    fun setStrSet(key: String, v: Set<String>) { ed()?.putStringSet(key, v)?.apply(); if (!remote) writeMediaSnapshot() else cache?.put(key, v) }

    // 快手进程收到模块 app 的值广播：更新内存缓存 + 持久化到快手本地 SP
    fun applyRemote(key: String, v: Any) {
        if (cache == null) cache = HashMap()
        cache?.put(key, v)
        persistRemoteLocal(key, v)
        Logger.d("prefs applyRemote $key")
    }

    private fun persistRemoteLocal(key: String, v: Any) {
        try {
            val e = rsp?.edit() ?: return
            when (v) {
                is Boolean -> e.putBoolean(key, v)
                is Int -> e.putInt(key, v)
                is Set<*> -> e.putStringSet(key, v as? Set<String> ?: emptySet())
                is String -> e.putString(key, v)
                else -> return
            }
            e.apply()
        } catch (_: Throwable) {}
    }

    /** 模块 app 打开时拉取：把快手进程当前 cache 全量回发（防旧值回滚用户的快手侧修改） */
    fun replyPull(ctx: Context) {
        val c = cache ?: return
        var n = 0
        for ((k, v) in c) {
            when (v) {
                is Boolean -> { sendWriteBroadcast(ctx, "bool", k, v); n++ }
                is Int -> { sendWriteBroadcast(ctx, "int", k, v); n++ }
                is Set<*> -> { sendWriteBroadcast(ctx, "strset", k, v); n++ }
                is String -> { sendWriteBroadcast(ctx, "str", k, v); n++ }
            }
        }
        Logger.d("prefs replyPull keys=$n")
    }

    private fun sendWriteBroadcast(ctx: Context, type: String, key: String, value: Any?) {
        try {
            val i = Intent(ACTION_WRITE).setPackage(OWN_PKG)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("type", type).putExtra("key", key)
            when (value) {
                is Boolean -> i.putExtra("value", value)
                is Int -> i.putExtra("value", value)
                is String -> i.putExtra("value", value)
                is Array<*> -> i.putExtra("value", value as Array<String>)
            }
            ctx.sendBroadcast(i)
        } catch (_: Throwable) {}
    }

    fun sendUpdateBroadcast(ctx: Context, type: String, key: String, value: Any?) {
        try {
            val i = Intent(ACTION_UPDATE)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("type", type).putExtra("key", key)
            when (value) {
                is Boolean -> i.putExtra("value", value)
                is Int -> i.putExtra("value", value)
                is String -> i.putExtra("value", value)
                is Set<*> -> {
                    // toTypedArray() 在 Set<*> 上静态类型为 Array<out Any?>，会落到
                    // putExtra(Serializable) 重载，接收端 getStringArrayExtra 恒为 null，
                    // strset 广播全丢。必须显式转 Array<String> 走 String[] 重载
                    val arr = value.map { it.toString() }.toTypedArray()
                    i.putExtra("value", arr)
                }
            }
            ctx.sendBroadcast(i)
        } catch (_: Throwable) {}
    }

    fun setBoolSync(ctx: Context, key: String, v: Boolean) {
        if (remote) { if (cache == null) cache = HashMap(); cache?.put(key, v); persistRemoteLocal(key, v); remoteWriteMedia("bool", key, v); sendWriteBroadcast(ctx, "bool", key, v) }
        else { setBool(key, v); sendUpdateBroadcast(ctx, "bool", key, v) }
    }
    fun setStrSetSync(ctx: Context, key: String, v: Set<String>) {
        if (remote) { if (cache == null) cache = HashMap(); cache?.put(key, v); persistRemoteLocal(key, v); remoteWriteMedia("strset", key, v); sendWriteBroadcast(ctx, "strset", key, v.toTypedArray()) }
        else { setStrSet(key, v); sendUpdateBroadcast(ctx, "strset", key, v) }
    }
    fun setIntSync(ctx: Context, key: String, v: Int) {
        if (remote) { if (cache == null) cache = HashMap(); cache?.put(key, v); persistRemoteLocal(key, v); remoteWriteMedia("int", key, v); sendWriteBroadcast(ctx, "int", key, v) }
        else { setInt(key, v); sendUpdateBroadcast(ctx, "int", key, v) }
    }
    fun setStrSync(ctx: Context, key: String, v: String) {
        if (remote) { if (cache == null) cache = HashMap(); cache?.put(key, v); persistRemoteLocal(key, v); remoteWriteMedia("str", key, v); sendWriteBroadcast(ctx, "str", key, v) }
        else { setStr(key, v); sendUpdateBroadcast(ctx, "str", key, v) }
    }

    fun toggleBool(ctx: Context, key: String, def: Boolean): Boolean {
        val nv = !bool(key, def)
        setBoolSync(ctx, key, nv)
        return nv
    }
}
