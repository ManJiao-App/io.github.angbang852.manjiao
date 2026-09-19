/**
 * Bitmap 对象池 - 减少 GC 压力
 * 
 * 功能：
 * 1. 复用 Bitmap 对象，避免频繁创建和销毁
 * 2. 减少内存分配，降低 GC 频率
 * 3. 提升渲染性能，减少卡顿
 * 
 * 使用示例：
 * ```kotlin
 * val pool = BitmapPool.getInstance()
 * val bitmap = pool.get(width, height, Bitmap.Config.ARGB_8888)
 * // 使用 bitmap...
 * pool.put(bitmap)  // 归还到池中
 * ```
 */
package com.example.liquidglass

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Bitmap 对象池（线程安全）
 */
class BitmapPool private constructor() {
    
    companion object {
        private const val TAG = "BitmapPool"
        private const val MAX_POOL_SIZE = 20  // 最大池大小（张数）
        private const val MAX_POOL_BYTES = 48L * 1024 * 1024  // ★ 字节上限：宿主进程内要克制（≈4-5 张全屏 ARGB_8888）
        private const val ENABLE_LOG = false  // 日志开关
        
        @Volatile
        private var instance: BitmapPool? = null
        
        fun getInstance(): BitmapPool {
            return instance ?: synchronized(this) {
                instance ?: BitmapPool().also { instance = it }
            }
        }
    }
    
    // 使用 ConcurrentHashMap 实现线程安全的对象池
    // Key: "width_height_config", Value: MutableList<Bitmap>
    private val pool = ConcurrentHashMap<String, MutableList<Bitmap>>()
    private var totalSize = 0
    private var totalBytes = 0L
    
    /**
     * 从池中获取 Bitmap
     * 
     * @param width 宽度
     * @param height 高度
     * @param config Bitmap 配置
     * @return 可复用的 Bitmap，如果池中没有则创建新的
     */
    fun get(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val key = makeKey(width, height, config)
        
        synchronized(pool) {
            val list = pool[key]
            if (!list.isNullOrEmpty()) {
                val bitmap = list.removeAt(list.size - 1)
                totalSize--
                totalBytes -= bitmap.byteCount
                
                if (ENABLE_LOG) {
                    Log.d(TAG, "♻️ 复用 Bitmap: ${width}x${height}, 池大小: $totalSize")
                }
                
                // 清空 Bitmap 内容
                bitmap.eraseColor(0)
                return bitmap
            }
        }
        
        // 池中没有，创建新的
        if (ENABLE_LOG) {
            Log.d(TAG, "🆕 创建新 Bitmap: ${width}x${height}")
        }
        
        return Bitmap.createBitmap(width, height, config)
    }
    
    /**
     * 归还 Bitmap 到池中
     * 
     * @param bitmap 要归还的 Bitmap
     * @return true 如果成功归还，false 如果池已满或 Bitmap 无效
     */
    fun put(bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.isRecycled) {
            return false
        }
        
        synchronized(pool) {
            val bmpBytes = bitmap.byteCount
            // ★ 字节上限：只按张数限会在大图上失控（20 张全屏 ARGB_8888 ≈ 200MB）
            if (totalSize >= MAX_POOL_SIZE || totalBytes + bmpBytes > MAX_POOL_BYTES) {
                if (ENABLE_LOG) {
                    Log.d(TAG, "⚠️ 池已满，回收 Bitmap: ${bitmap.width}x${bitmap.height}")
                }
                bitmap.recycle()
                return false
            }

            val key = makeKey(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
            val list = pool.getOrPut(key) { mutableListOf() }

            list.add(bitmap)
            totalSize++
            totalBytes += bmpBytes
            
            if (ENABLE_LOG) {
                Log.d(TAG, "✅ 归还 Bitmap: ${bitmap.width}x${bitmap.height}, 池大小: $totalSize")
            }
            
            return true
        }
    }
    
    /**
     * 清空对象池
     */
    fun clear() {
        synchronized(pool) {
            pool.values.forEach { list ->
                list.forEach { it.recycle() }
                list.clear()
            }
            pool.clear()
            totalSize = 0
            totalBytes = 0L
            
            if (ENABLE_LOG) {
                Log.d(TAG, "🗑️ 清空对象池")
            }
        }
    }
    
    /**
     * 获取池的统计信息
     */
    fun getStats(): String {
        synchronized(pool) {
            return "BitmapPool: 总数=$totalSize, 类型数=${pool.size}"
        }
    }
    
    /**
     * 生成缓存键
     */
    private fun makeKey(width: Int, height: Int, config: Bitmap.Config): String {
        return "${width}_${height}_${config.name}"
    }
}

