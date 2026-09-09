package io.github.angbang852.manjiao.hook

import android.app.Activity
import android.content.Context
import android.net.Uri
import io.github.angbang852.manjiao.KsClass
import io.github.angbang852.manjiao.data.CurrentVideo
import io.github.angbang852.manjiao.data.RepUrl
import io.github.angbang852.manjiao.util.Logger
import io.github.angbang852.manjiao.util.Reflect
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.net.URL

object VideoDownloaderHook {

    fun hook(xp: XposedInterface, cl: ClassLoader) {
        hookPlayer(xp, cl)
        hookDetail(xp, cl)
        hookOkHttp(xp, cl)
        hookAllOnResume(xp, cl)
        hookUrlConstructor(xp, cl)
        hookRepresentations(xp, cl)
        Logger.d("VideoHook installed")
    }

    private fun hookRepresentations(xp: XposedInterface, cl: ClassLoader) {
        Logger.safe("hookRep") {
            val repClasses = mutableSetOf<String>()
            val dexCl = Class.forName("dalvik.system.BaseDexClassLoader", false, cl)
            val pathListField = dexCl.getDeclaredField("pathList").apply { isAccessible = true }
            val pathList: Any = pathListField.get(cl) ?: return@safe
            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
            val elements = dexElementsField.get(pathList) as Array<*>
            for (e in elements) {
                val elem: Any = e ?: continue
                val dexFileField = elem.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }
                val dexFile = dexFileField.get(elem) ?: continue
                val entriesMethod = dexFile.javaClass.getMethod("entries")
                @Suppress("UNCHECKED_CAST")
                val entries = entriesMethod.invoke(dexFile) as java.util.Enumeration<String>
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement()
                    if (name.contains("Representation") && !name.contains("$")
                        && !name.contains("Activity") && !name.contains("Fragment")
                        && !name.contains("Adapter") && !name.contains("View")) {
                        repClasses.add(name)
                    }
                }
            }
            Logger.d("Rep classes: ${repClasses.size}")
            for (cn in repClasses) {
                val c = Reflect.findClass(cn, cl) ?: continue
                for (ctor in c.declaredConstructors) {
                    if (ctor.parameterTypes.size > 5) continue
                    Logger.safe("hookRep.$cn") {
                        xp.hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("rep.$cn").intercept { chain ->
                            chain.proceed()
                            try { captureRep(chain.thisObject, cn) } catch (_: Throwable) {}
                            null
                        }
                    }
                }
                if (cn.contains("KwaiRepresentation") && !cn.contains("$")) {
                    for (m in c.declaredMethods) {
                        if (m.name.startsWith("set") && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                            Logger.safe("hookRepSet.$cn.${m.name}") {
                                xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("repset.${m.name}").intercept { chain ->
                                    chain.proceed()
                                    try { captureRep(chain.thisObject, cn) } catch (_: Throwable) {}
                                    null
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun captureRep(obj: Any, cn: String) {
        val url = Reflect.readString(obj, "url", "playUrl", "cdnUrl", "mUrl", "videoUrl")
        if (url == null || url.length < 8) {
            if (cn.contains("KwaiRep")) Logger.d("Rep ctor no url: $cn fields=${obj.javaClass.declaredFields.size}")
            return
        }
        if (!url.contains(".mp4") && !url.contains(".flv")) return
        if (!KsClass.VIDEO_HOST_HINTS.any { url.contains(it) }) return
        val h = Reflect.readLong(obj, "height", "videoHeight", "mHeight")
        val br = Reflect.readLong(obj, "avgBitreate", "avgBitrate", "bitRate", "bitrate", "mBitRate")
        val w = Reflect.readLong(obj, "width", "videoWidth", "mWidth")
        CurrentVideo.update {
            repUrls.removeAll { it.url == url }
            repUrls.add(RepUrl(url, h, br))
            if (repUrls.size > 20) repUrls.subList(0, repUrls.size - 20).clear()
        }
        Logger.d("Rep captured: ${w}x${h} br=$br url=$url")
    }

    private fun hookAllOnResume(xp: XposedInterface, cl: ClassLoader) {
        Logger.safe("hookAllOnResume") {
            val m = Activity::class.java.getDeclaredMethod("onResume")
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("act.onResume").intercept { chain ->
                chain.proceed()
                val name = chain.thisObject.javaClass.name
                if (name.contains("Detail") || name.contains("Photo") || name.contains("Video") || name.contains("Play") || name.contains("Home")) {
                    Logger.d("onResume: $name")
                    try { extractMeta(chain.thisObject as Activity) } catch (t: Throwable) { Logger.d("extractMeta2 err: $t") }
                }
                null
            }
        }
    }

    private fun hookUrlConstructor(xp: XposedInterface, cl: ClassLoader) {
        Logger.safe("hookUrlConstructor") {
            val c = URL::class.java.getConstructor(String::class.java)
            xp.hook(c).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("url.ctor").intercept { chain ->
                val url = chain.args[0] as? String ?: ""
                if (url.length > 8 && KsClass.VIDEO_HOST_HINTS.any { url.contains(it) }) {
                    tryCapture(url, "URL")
                }
                chain.proceed()
                null
            }
        }
    }

    private fun hookPlayer(xp: XposedInterface, cl: ClassLoader) {
        Logger.safe("MediaPlayer") {
            val mp = Class.forName("android.media.MediaPlayer", false, cl)
            for (m in mp.declaredMethods) {
                if (m.name != "setDataSource") continue
                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                    hookUrl(xp, m, 0)
                }
                if (m.parameterTypes.size == 2 && m.parameterTypes[0] == Context::class.java && m.parameterTypes[1] == Uri::class.java) {
                    xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("mp.ds.uri").intercept { chain ->
                        tryCapture((chain.args[1] as? Uri)?.toString() ?: "", "MediaPlayer"); chain.proceed()
                    }
                }
            }
        }
        for (cn in arrayOf("com.kwai.player.KwaiPlayer", "com.kwai.player.AemonPlayer", "com.kuaishou.player.KwaiPlayer")) {
            val c = Reflect.findClass(cn, cl) ?: continue
            for (m in c.declaredMethods) {
                if (m.parameterTypes.size != 1) continue
                val pt = m.parameterTypes[0]
                if (pt != String::class.java && pt != Uri::class.java) continue
                if (m.name !in setOf("setDataSource", "setUrl", "setVideoPath", "openVideo")) continue
                Logger.safe("hook ${cn}.${m.name}") { hookUrl(xp, m, 0) }
            }
        }
    }

    private fun hookUrl(xp: XposedInterface, m: Method, argIdx: Int) {
        xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("url.${m.name}").intercept { chain ->
            tryCapture(chain.args[argIdx]?.toString() ?: "", "player"); chain.proceed()
        }
    }

    private fun tryCapture(url: String, tag: String) {
        if (url.length < 8) return
        val low = url.lowercase()
        if (!KsClass.VIDEO_HOST_HINTS.any { low.contains(it) }) return
        if (!low.contains(".mp4") && !low.contains(".flv")) return
        val isIp = Regex("^https?://\\d+\\.\\d+").containsMatchIn(low)
        CurrentVideo.update {
            if (isIp) {
                this.url = url
            } else {
                this.domainUrl = url
                if (this.url == null) this.url = url
            }
        }
        Logger.d("capture[$tag${if (isIp) "/IP" else "/DOM"}]: $url")
    }

    private fun hookDetail(xp: XposedInterface, cl: ClassLoader) {
        for (a in arrayOf(KsClass.PHOTO_DETAIL_ACTIVITY, KsClass.PHOTO_DETAIL_ACTIVITY_TABLET)) {
            val c = Reflect.findClass(a, cl) ?: continue
            val m = Reflect.findMethod(c, "onResume", 0) ?: continue
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("detail.$a").intercept { chain ->
                chain.proceed(); Logger.d("detail onResume: $a"); try { extractMeta(chain.thisObject as Activity) } catch (t: Throwable) { Logger.d("extractMeta err: $t") }; null
            }
            Logger.d("hook $a")
        }
    }

    private fun extractMeta(act: Activity) {
        Logger.safe("extractMeta") {
            var photo = findPhoto(act)
            if (photo == null) { Logger.d("findPhoto null on ${act.javaClass.name}"); return@safe }
            if (photo.javaClass.name == "com.yxcorp.gifshow.entity.QPhoto") {
                val ent = Reflect.readAny(photo, "mEntity")
                if (ent != null) {
                    Logger.d("QPhoto mEntity: ${ent.javaClass.name}")
                    photo = ent
                }
            }
            val imgIs = isImage(photo)
            val imgs = if (imgIs) extractImageUrls(photo) else emptyList()
            CurrentVideo.update {
                coverUrl = Reflect.readString(photo, *KsClass.COVER_FIELDS)
                caption = Reflect.readString(photo, *KsClass.CAPTION_FIELDS)
                userName = Reflect.readString(photo, *KsClass.USER_NAME_FIELDS)
                userId = Reflect.readString(photo, *KsClass.USER_ID_FIELDS)
                duration = Reflect.readLong(photo, *KsClass.DURATION_FIELDS)
                likeCount = Reflect.readLong(photo, *KsClass.LIKE_COUNT_FIELDS)
                viewCount = Reflect.readLong(photo, *KsClass.VIEW_COUNT_FIELDS)
                photoType = Reflect.readString(photo, *KsClass.PHOTO_TYPE_FIELDS)
                audioUrl = Reflect.readString(photo, *KsClass.AUDIO_URL_FIELDS)
                isAd = isAd(photo); isLive = isLive(photo); isImage = imgIs
                isAi = isAi(photo, this.caption); isEcommerce = isEc(photo, this.caption)
                pickBestUrl(photo)?.let { this.url = it }
                if (imgs.isNotEmpty()) {
                    imageUrls.clear()
                    imageUrls.addAll(imgs)
                }
            }
            Logger.d("meta: ${CurrentVideo.current.userName} | like=${CurrentVideo.current.likeCount} | ad=${CurrentVideo.current.isAd} | img=${CurrentVideo.current.imageUrls.size}")
        }
    }

    private fun extractImageUrls(photo: Any): List<String> {
        val urls = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        try {
            val im = Reflect.readAny(photo, "mImageModel")
            val atlas = im?.let { Reflect.readAny(it, "mAtlas") }
            if (atlas != null) {
                val cdns = (Reflect.readAny(atlas, "mCdn") as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
                val arr = Reflect.readAny(atlas, "mList") as? Array<*> ?: emptyArray<Any?>()
                for (u in arr) {
                    if (u !is String || u.length < 8 || !seen.add(u)) continue
                    var full = if (u.startsWith("http")) u
                    else {
                        val host = cdns.firstOrNull() ?: "p2aoc.app1104756551.qqopenapp.com"
                        "https://$host$u"
                    }
                    if (full.endsWith(".kvif")) full = full.removeSuffix(".kvif") + ".jpg"
                    urls.add(full)
                }
                Logger.d("atlas mList: ${urls.size} imgs cdn=${cdns.firstOrNull()} first=${urls.firstOrNull()?.take(100)}")
            }
            if (urls.isEmpty()) {
                val single = im?.let { Reflect.readAny(it, "mSinglePicture") }
                val u = single?.let { Reflect.readString(it, "mUrl", "url", "mCdn", "cdn") }
                if (u != null && u.length > 8) { urls.add(u); Logger.d("singlePic: 1 img") }
            }
        } catch (t: Throwable) { Logger.d("atlas extract err: ${t.message}") }
        if (urls.isNotEmpty()) return urls
        var c: Class<*>? = photo.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c!!.declaredFields) {
                if (!java.util.List::class.java.isAssignableFrom(f.type)) continue
                try {
                    f.isAccessible = true
                    val list = f.get(photo) as? List<*> ?: continue
                    var cnt = 0
                    for (item in list) {
                        if (item == null) continue
                        val u: String? = if (item is String) item
                        else Reflect.readString(item, "url", "cdnUrl", "imageUrl", "webpUrl", "mUrl", "picUrl", "originUrl")
                        if (u != null && u.length > 8 && seen.add(u)) {
                            urls.add(u); cnt++
                        }
                    }
                    if (cnt > 0) Logger.d("imgUrls from ${f.name}: $cnt")
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return urls
    }

    private fun pickBestUrl(photo: Any): String? {
        for (fn in KsClass.REP_LIST_FIELDS) {
            val list = Reflect.readAny(photo, fn) ?: continue
            if (list !is List<*>) continue
            var best: String? = null
            var bestScore = -1L
            for (rep in list) {
                if (rep == null) continue
                val u = Reflect.readString(rep, *KsClass.REP_URL_FIELDS) ?: continue
                if (!KsClass.VIDEO_HOST_HINTS.any { u.contains(it) }) continue
                val h = Reflect.readLong(rep, *KsClass.REP_HEIGHT_FIELDS)
                val br = Reflect.readLong(rep, *KsClass.REP_BITRATE_FIELDS)
                val score = h * 10000 + br
                if (score > bestScore) { bestScore = score; best = u }
            }
            if (best != null) return best
        }
        return null
    }

    private fun findPhoto(act: Activity): Any? {
        var c: Class<*>? = act.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c!!.declaredFields) {
                if (f.type.isPrimitive || f.type == String::class.java || f.type.name.startsWith("android.")) continue
                try {
                    f.isAccessible = true; val v = f.get(act) ?: continue
                    val n = v.javaClass.name
                    if (n.contains("Photo") && !n.contains("Adapter") && !n.contains("Fragment") && !n.contains("View") && !n.contains("Activity")) {
                        if (n.contains("Param") || n.contains("Router")) {
                            val inner = findPhotoInObj(v)
                            if (inner != null) return inner
                            Logger.d("findPhoto Param fields: ${v.javaClass.declaredFields.map { it.name + ":" + it.type.simpleName }}")
                        }
                        return v
                    }
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return null
    }

    private fun findPhotoInObj(obj: Any): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c!!.declaredFields) {
                if (f.type.isPrimitive || f.type == String::class.java || f.type.name.startsWith("android.")) continue
                try {
                    f.isAccessible = true; val v = f.get(obj) ?: continue
                    val n = v.javaClass.name
                    if (n.contains("Photo") && !n.contains("Adapter") && !n.contains("Fragment") && !n.contains("View") && !n.contains("Activity") && !n.contains("Param") && !n.contains("Router")) return v
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return null
    }

    private fun isAd(p: Any): Boolean {
        val n = p.javaClass.name
        return n.contains("Advertise") || n.contains("Ad") || Reflect.readBool(p, "isAd", "mIsAd", "ad")
    }
    private fun isLive(p: Any): Boolean {
        val pt = Reflect.readString(p, *KsClass.PHOTO_TYPE_FIELDS) ?: ""
        return pt.contains("live", true) || Reflect.readBool(p, "isLive", "mIsLive")
    }
    private fun isImage(p: Any): Boolean {
        val n = p.javaClass.name
        if (n.contains("ImageFeed") || n.contains("Atlas")) return true
        if (Reflect.readAny(p, "mImageModel") != null) return true
        val pt = Reflect.readString(p, *KsClass.PHOTO_TYPE_FIELDS) ?: ""
        return pt.contains("image", true) || pt.contains("atlas", true) || !Reflect.readBool(p, *KsClass.IS_VIDEO_FIELDS)
    }
    private fun isAi(p: Any, cap: String?): Boolean {
        val pt = Reflect.readString(p, *KsClass.PHOTO_TYPE_FIELDS) ?: ""
        return pt.contains("ai", true) || (cap?.contains("AI", true) == true) || Reflect.readBool(p, "isAi", "mIsAi")
    }
    private fun isEc(p: Any, cap: String?): Boolean {
        val n = p.javaClass.name
        return n.contains("ecommerce", true) || n.contains("merchant", true) ||
            (cap?.contains("购物", true) == true) || (cap?.contains("小黄车", true) == true) ||
            Reflect.readBool(p, "isEcommerce", "mIsEcommerce")
    }

    private fun hookOkHttp(xp: XposedInterface, cl: ClassLoader) {
        val req = Reflect.findClass("okhttp3.Request", cl) ?: return
        val client = Reflect.findClass("okhttp3.OkHttpClient", cl) ?: return
        Logger.safe("okhttp") {
            val m = Reflect.findMethod(client, "newCall", 1) ?: return@safe
            xp.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("okhttp.newCall").intercept { chain ->
                tryCapture(Reflect.callMethod(chain.args[0], "url")?.toString() ?: "", "okhttp"); chain.proceed()
            }
        }
    }
}
