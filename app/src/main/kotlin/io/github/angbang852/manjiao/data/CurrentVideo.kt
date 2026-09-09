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
        val pid = url?.let { Regex("\\d{15,}").find(it)?.value }
        return repUrls
            .filter { it.url.contains(".mp4") || it.url.contains(".flv") }
            .filter { pid == null || it.url.contains(pid) }
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
        return sb.toString().trimEnd('_')
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
