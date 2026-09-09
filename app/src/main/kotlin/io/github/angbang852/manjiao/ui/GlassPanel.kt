package io.github.angbang852.manjiao.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.widget.FrameLayout
import kotlin.math.max

/**
 * 液态玻璃面板 —— 采用 WeChat-LiquidGlass（liuran001/mmliquidglass）的配方结构。
 *
 * 原版 KernelSU 效果栈：vibrancy(sat 1.5) → blur(4dp) → lens(边缘 24dp SDF 折射)
 * → surface wash(surfaceContainer 40%) → rim highlight(1dp)。
 *
 * backdrop 差异：微信列表是普通 View，page.draw() 可采样，全链 AGSL 在位图上跑；
 * 快手视频是 SurfaceView（独立 compositor layer，View 体系采不到样），故 blur
 * 交给 SurfaceFlinger 跨窗模糊（FLAG_BLUR_BEHIND + LayoutParams.blurBehindRadius，
 * API 31+）对视频 layer 实时执行——零采样、天然同步、零开销。SF 模糊区域 =
 * window 边界，因此承载本面板的 Dialog window 缩到面板大小（attachOverlay）。
 * vibrancy / SDF 折射需要读到背景像素，SF 模糊结果采不到：折射以 bevel 边缘
 * 渐变近似，vibrancy 舍弃。
 *
 * 表面层参数照抄 LiquidGlassPanel / LiquidGlassHostLayout：
 *   - dropShadow(10dp, offset 0/2dp, alpha 亮 0.1 / 暗 0.2)，fill 挖除只留外溢影
 *   - wash = surfaceContainer.copy(0.4f)
 *   - gloss = 顶部 45% 高度渐变 0x30FFFFFF → 透明
 *   - rim = 1dp 描边 0x2EFFFFFF（亮）/ 0x1FFFFFFF（暗）
 *
 * 结构照抄 HostLayout.setupShadow：四周 reserve 14dp 投影 padding，子内容
 * shrink 到内框，投影画在 padding 区，不被 window 边界裁掉。
 */
class GlassPanel(
    ctx: Context,
    private val cornerRadiusPx: Float,
    /** wash 覆盖色（含 alpha）；null=用默认 surfaceContainer.copy(0.4f)。
     *  app 菜单背景为静态图片（无 SF 跨窗模糊加持）时传更高 alpha 弥补雾面感。 */
    private val washOverride: Int? = null
) : FrameLayout(ctx) {

    private val density = resources.displayMetrics.density
    private val dark = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** 投影外溢 reserve（照抄 HostLayout.setupShadow 的 14dp）。 */
    val shadowPad = Math.round(density * 14f)

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }


    private val bounds = RectF()
    private val shape = Path()

    init {
        setWillNotDraw(false)
        setPadding(shadowPad, shadowPad, shadowPad, shadowPad)
        // KernelSU: dropShadow(radius = 10.dp, alpha = dark ? 0.2f : 0.1f)
        shadowPaint.color = 0xFF000000.toInt()
        shadowPaint.setShadowLayer(
            density * 10f, 0f, density * 2f,
            if (dark) 0x33000000 else 0x1A000000
        )
        // surfaceContainer.copy(0.4f)
        washPaint.color = washOverride ?: (if (dark) 0x662F3036 else 0x66EFF1F7)
        // rim highlight 1dp
        rimPaint.strokeWidth = max(density, 0.75f)
        rimPaint.color = if (dark) 0x1FFFFFFF else 0x2EFFFFFF
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // 内框（投影 padding 之内）——HostLayout：children shrink into the inner box
        bounds.set(
            shadowPad.toFloat(), shadowPad.toFloat(),
            (w - shadowPad).toFloat(), (h - shadowPad).toFloat()
        )
        shape.reset()
        shape.addRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        // gloss：顶部 45% 高度渐变（HostLayout 参数）
        glossPaint.shader = LinearGradient(
            0f, bounds.top, 0f, bounds.top + bounds.height() * 0.45f,
            if (dark) 0x1FFFFFFF else 0x30FFFFFF,
            0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        drawDropShadow(canvas)
        // 原版表面层只有三笔：wash → gloss → rim(1dp)。bevel/glint 已删
        // （直透模式的假光感，有真模糊后是多余的粗边框）
        canvas.drawPath(shape, washPaint)
        canvas.drawPath(shape, glossPaint)
        val half = rimPaint.strokeWidth / 2f
        canvas.save()
        canvas.translate(half, half)
        canvas.drawPath(shape, rimPaint)
        canvas.restore()
    }

    /** HostLayout.drawPillShadow：不透明圆角矩形只为投影，pill 区域挖除防黑边。 */
    private fun drawDropShadow(canvas: Canvas) {
        if (Build.VERSION.SDK_INT < 26) return
        val save = canvas.save()
        canvas.clipOutPath(shape)
        canvas.drawPath(shape, shadowPaint)
        canvas.restoreToCount(save)
    }
}
