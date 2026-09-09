package io.github.angbang852.manjiao.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.angbang852.manjiao.util.Logger

class PrefsWriteReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Prefs.initLocal(ctx)
        when (i.action) {
            Prefs.ACTION_QUERY -> Prefs.broadcastAll(ctx)
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
