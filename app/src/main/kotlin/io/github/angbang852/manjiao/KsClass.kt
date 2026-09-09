package io.github.angbang852.manjiao

object KsClass {
    const val PKG = "com.smile.gifmaker"
    const val PKG_NEBULA = "com.kuaishou.nebula"

    const val PHOTO_DETAIL_ACTIVITY = "com.yxcorp.gifshow.detail.PhotoDetailActivity"
    const val PHOTO_DETAIL_ACTIVITY_TABLET = "com.yxcorp.gifshow.detail.PhotoDetailActivityTablet"
    const val PHOTO_DETAIL_FRAGMENT = "com.yxcorp.gifshow.detail.PhotoDetailFragment"
    const val COMMENT_FRAGMENT = "com.yxcorp.gifshow.detail.CommentFragment"

    const val KWAI_REPRESENTATION = "com.kwai.player.KwaiRepresentation"

    // 视频字段候选（混淆容错）
    val VIDEO_URL_FIELDS = arrayOf("videoUrl", "playUrl", "mainUrl", "cdnUrl", "photoUrl", "url", "mUrl")
    val COVER_FIELDS = arrayOf("coverUrl", "webpCover", "poster", "cover", "mCoverUrl")
    val CAPTION_FIELDS = arrayOf("caption", "desc", "description", "title", "mCaption")
    val USER_NAME_FIELDS = arrayOf("userName", "name", "nickName", "nickname", "mUserName")
    val USER_ID_FIELDS = arrayOf("userId", "uid", "id", "mUserId")
    val DURATION_FIELDS = arrayOf("duration", "mDuration", "videoDuration")
    val IS_VIDEO_FIELDS = arrayOf("isVideo", "mIsVideo", "video")
    val PHOTO_TYPE_FIELDS = arrayOf("photoType", "type", "mType", "photoTypeStr")
    val LIKE_COUNT_FIELDS = arrayOf("likeCount", "likedCount", "realLikeCount", "count", "mLikeCount")
    val VIEW_COUNT_FIELDS = arrayOf("viewCount", "playCount", "watchCount", "mViewCount")
    val AUDIO_URL_FIELDS = arrayOf("audioUrl", "musicUrl", "mAudioUrl")

    // representations 列表字段候选
    val REP_LIST_FIELDS = arrayOf("representations", "representationList", "multiUrl", "urls", "mRepresentations")
    // representation 内部 url/高度/码率字段
    val REP_URL_FIELDS = arrayOf("url", "playUrl", "cdnUrl", "mUrl")
    val REP_HEIGHT_FIELDS = arrayOf("height", "videoHeight", "mHeight")
    val REP_BITRATE_FIELDS = arrayOf("bitRate", "bitrate", "avgBitrate", "mBitRate")

    val VIDEO_HOST_HINTS = arrayOf(
        "yximgs.com", "kwimgs.com", "kwaicdn.com",
        "wsukwai.com", "wskwai.com", "eckwai.com", "adukwai.com",
        "shwkwai.com", "shukwai.com", "sbkwai.com", "beckwai.com"
    )
    val VIDEO_EXT_HINTS = arrayOf(".mp4", ".flv", ".m3u8", ".ts", ".mov", ".webm")

    // 分享面板识别关键词
    val SHARE_PANEL_TEXTS = arrayOf("微信", "QQ", "复制", "分享", "推荐", "保存", "举报", "朋友圈", "好友", "微博", "链接")

    // 可隐藏的 UI 文字（沉浸式选择隐藏）
    val HIDABLE_ITEMS = arrayOf(
        "点赞", "评论", "分享", "收藏", "关注",
        "作者头像", "作者昵称", "视频文案",
        "顶部栏", "底部Tab栏", "音乐旋转",
        "倒计时", "进度条", "倍速", "清晰度"
    )
}