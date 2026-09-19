package io.github.angbang852.manjiao.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.angbang852.manjiao.util.Logger

class PrefsWriteReceiver : BroadcastReceiver() {
    // ★ QUERY 应答限频：receiver 是 exported（快手进程是无权限的对端，signature 级
    // permission 会直接切断同步链路，只能靠运行时自律），应答会连发 N 条全量广播，
    // 任何本地 app 都能发 PREFS_QUERY 放大刷屏。3 秒限频不影响正常同步（正常周期 ≥30s）
    private var lastQueryAt = 0L

    override fun onReceive(ctx: Context, i: Intent) {
        Prefs.initLocal(ctx)
        when (i.action) {
            Prefs.ACTION_QUERY -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastQueryAt < 3000) return
                lastQueryAt = now
                Prefs.broadcastAll(ctx)
            }
            Prefs.ACTION_WRITE -> {
                val type = i.getStringExtra("type") ?: return
                val key = i.getStringExtra("key") ?: return
                // 写入后回发 ACTION_UPDATE：快手侧 mediaDead（媒体文件 EACCES）时
                // 拉不到媒体文件新值，不回广播则 adb/跨端写入永远同步不到快手
                when (type) {
                    "bool" -> { val v = i.getBooleanExtra("value", false); Prefs.setBool(key, v); Prefs.sendUpdateBroadcast(ctx, "bool", key, v) }
                    "strset" -> { val v = (i.getStringArrayExtra("value") ?: emptyArray()).toSet(); Prefs.setStrSet(key, v); Prefs.sendUpdateBroadcast(ctx, "strset", key, v) }
                    "int" -> { val v = i.getIntExtra("value", 0); Prefs.setInt(key, v); Prefs.sendUpdateBroadcast(ctx, "int", key, v) }
                    "str" -> { val v = i.getStringExtra("value") ?: ""; Prefs.setStr(key, v); Prefs.sendUpdateBroadcast(ctx, "str", key, v) }
                }
                Logger.d("prefs write $type $key")
            }
        }
    }
}
