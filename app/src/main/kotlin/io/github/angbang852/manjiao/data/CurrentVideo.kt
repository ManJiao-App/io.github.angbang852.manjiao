package io.github.angbang852.manjiao.data

data class RepUrl(val url: String, val height: Long, val bitrate: Long)

data class VideoInfo(
    var url: String? = null,
    var domainUrl: String? = null,
    var audioUrl: String? = null,
    var coverUrl: String? = null,
    var caption: String? = null,
    var userName: String? = null,
    var userId: String? = null,
    var duration: Long = 0L,
    var likeCount: Long = 0L,
    var viewCount: Long = 0L,
    var isAd: Boolean = false,
    var isLive: Boolean = false,
    var isImage: Boolean = false,
    var isAi: Boolean = false,
    var isEcommerce: Boolean = false,
    var photoType: String? = null,
    val repUrls: MutableList<RepUrl> = mutableListOf(),
    val imageUrls: MutableList<String> = mutableListOf()
) {
    fun valid() = !url.isNullOrEmpty() || imageUrls.isNotEmpty()

    fun bestRepUrl(): String? {
        // ★ pid 提不出（url 为空或无 15 位数字的直链/IP 直链）时不放行任何候选：
        // 否则会在 repUrls 历史里跨视频挑清晰度最高的一条（内容错配）；
        // 此场景由 downloadVideo 的候选回退（info.url/domainUrl）兜底
        val pid = url?.let { Regex("\\d{15,}").find(it)?.value } ?: return null
        return repUrls
            .filter { it.url.contains(".mp4") || it.url.contains(".flv") }
            .filter { it.url.contains(pid) }
            .maxByOrNull { it.height * 10000 + it.bitrate }
            ?.url
    }

    fun baseName(): String {
        val sb = StringBuilder()
        userName?.takeIf { it.isNotBlank() }?.let {
            sb.append(it.replace(Regex("[\\\\/:*?\"<>|]"), "_")).append("_")
        }
        caption?.takeIf { it.isNotBlank() }?.let {
            sb.append(it.take(40).replace(Regex("[\\\\/:*?\"<>|\\s]"), "_"))
        }
        if (sb.isEmpty()) {
            val id = url?.let { Regex("\\d{15,}").find(it)?.value }
            if (id != null) sb.append("ks_").append(id)
            else sb.append("ks_").append(System.currentTimeMillis() / 1000)
        }
        // ★ ext4 单文件名分量上限 255 字节（中文/emoji 按字符 3-4 字节算），
        // 超长昵称会让下载必失败——整体截断兜底
        return sb.toString().take(80).trimEnd('_')
    }

    fun videoFileName() = baseName() + ".mp4"
    fun audioFileName() = baseName() + ".m4a"
}

object CurrentVideo {
    @Volatile var current: VideoInfo = VideoInfo()
    fun reset() { current = VideoInfo() }
    fun update(block: VideoInfo.() -> Unit) {
        synchronized(this) {
            if (!current.valid()) current = VideoInfo()
            current.block()
        }
    }
}
