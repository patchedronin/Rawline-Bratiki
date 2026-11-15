package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Weathered steel and braided cables are rasterised once; only the light, arcs and smoke animate. */
class IndustrialOrbArtwork(context: Context, private val idleText: String) : MobileOrbArtwork(context, idleText) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var offLayer: Bitmap? = null
    private var onLayer: Bitmap? = null
    private var cachedSize = 0
    private var haze: Shader? = null
    private var smoke: Shader? = null
    private var lastFrame = 0L
    private var light = 0f
    private var caption = ""
    private var upperCaption = ""
    private val cyan = Color.rgb(53, 224, 255)
    private val whiteHot = Color.rgb(215, 253, 255)
    private val deepSteel = Color.rgb(29, 33, 37)
    private val hull = Path()
    private val glyph = Path().apply {
        addArc(RectF(-.24f, -.24f, .24f, .24f), -54f, 288f)
        moveTo(0f, -.32f); lineTo(0f, -.045f)
    }
    private val motionAllowed get() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val disc = if (discSize > 0) discSize else min(bounds.width(), bounds.height())
        val radius = MobileOrbArtwork.radius(disc)
        ensureLayers(radius)
        val now = SystemClock.uptimeMillis()
        val elapsed = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1, 64).toFloat()
        lastFrame = now
        val target = if (connected) 1f else if (connecting) .55f else 0f
        val moving = motionAllowed
        light = if (moving) light + (target - light) * (1f - exp(-elapsed / 280f)) else target
        if (abs(light - target) < .003f) light = target
        val active = connected || connecting || light != target
        val phase = if (moving) (now % 120000L) / 1000f else 0f
        val cx = bounds.exactCenterX()
        val cy = bounds.top + MobileOrbArtwork.centerY(disc)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius * pressScale, radius * pressScale)
        paint.reset(); paint.isAntiAlias = true; paint.isFilterBitmap = true
        if (light > .01f) {
            paint.shader = haze
            paint.alpha = (light * (if (moving) .86f + .14f * sin(phase * 2.3f) else 1f) * 255).toInt()
            canvas.drawCircle(0f, 0f, 1.42f, paint)
            paint.shader = null; paint.alpha = 255
        }
        rect.set(-1.42f, -1.42f, 1.42f, 1.42f)
        offLayer?.let { canvas.drawBitmap(it, null, rect, paint) }
        if (light > .01f) {
            paint.alpha = (255 * light).toInt()
            onLayer?.let { canvas.drawBitmap(it, null, rect, paint) }
            paint.alpha = 255
            drawSmoke(canvas, phase)
            drawElectricity(canvas, phase)
        }
        if (focused) {
            paint.shader = null; paint.style = Paint.Style.STROKE
            paint.strokeWidth = .012f; paint.color = MobilePalette.text
            canvas.drawCircle(0f, 0f, 1.30f, paint)
        }
        canvas.restore()
        drawCaption(canvas, cx, cy, radius)
        val host = callback as? View
        if (active && moving && !MobileOrbArtwork.frozen && host != null && host.isShown && host.hasWindowFocus()) {
            host.postInvalidateOnAnimation()
        }
    }

    private fun ensureLayers(radius: Float) {
        val size = (radius * 2.84f).toInt().coerceIn(256, 1280)
        if (size == cachedSize) return
        cachedSize = size
        // Let the renderer release old bitmaps after any in-flight display list has finished with them.
        offLayer = renderHardware(size, false)
        onLayer = renderHardware(size, true)
        haze = RadialGradient(0f, 0f, 1.42f,
            intArrayOf(Color.TRANSPARENT, 0x0035E0FF, 0x4435DFFF, Color.TRANSPARENT),
            floatArrayOf(0f, .50f, .75f, 1f), Shader.TileMode.CLAMP)
        smoke = RadialGradient(0f, 0f, 1f,
            intArrayOf(0x777F8995, 0x4B536170, Color.TRANSPARENT),
            floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
    }

    private fun renderHardware(size: Int, powered: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(size / 2f, size / 2f)
        canvas.scale(size / 2.84f, size / 2.84f)
        paint.reset(); paint.isAntiAlias = true
        val random = Random(87341)
        drawCables(canvas)
        // Segmented, slightly irregular cast housing rather than a thin flat ring.
        hull.rewind()
        for (i in 0..180) {
            val angle = i * (2f * PI.toFloat() / 180)
            val r = 1.02f + .016f * sin(angle * 9f) + .009f * cos(angle * 17f)
            val x = cos(angle) * r; val y = sin(angle) * r
            if (i == 0) hull.moveTo(x, y) else hull.lineTo(x, y)
        }
        hull.close()
        paint.style = Paint.Style.FILL; paint.color = Color.BLACK
        canvas.save(); canvas.translate(.016f, .036f); canvas.drawPath(hull, paint); canvas.restore()
        paint.shader = LinearGradient(-.8f, -.9f, .7f, 1f,
            intArrayOf(0xFF11161A.toInt(), 0xFF78818A.toInt(), 0xFF383C3F.toInt(), 0xFF1C1C1B.toInt(), 0xFF636B70.toInt()),
            floatArrayOf(0f, .22f, .45f, .70f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(hull, paint)
        paint.shader = null
        // Cast-metal texture and corrosion stay deterministic between off/on layers.
        canvas.save(); canvas.clipPath(hull)
        for (i in 0 until 3600) {
            val x = random.nextFloat() * 2.1f - 1.05f
            val y = random.nextFloat() * 2.1f - 1.05f
            paint.color = if (i % 7 == 0) Color.argb(48, 130, 80, 43)
                else if (i % 2 == 0) Color.argb(36, 220, 227, 230) else Color.argb(60, 0, 0, 0)
            canvas.drawCircle(x, y, .0015f + random.nextFloat() * .005f, paint)
        }
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 230) {
            val a = random.nextFloat() * 2f * PI.toFloat()
            val radius = .86f + random.nextFloat() * .14f
            val x = cos(a) * radius; val y = sin(a) * radius
            paint.color = if (i % 3 == 0) 0x705E4030 else 0x60495056
            paint.strokeWidth = .0015f
            canvas.drawLine(x, y, x + .015f + random.nextFloat() * .045f, y - .005f, paint)
        }
        canvas.restore()
        // Three bevels catch opposing highlights, and a deep black gasket separates the cavity.
        rim(canvas, .996f, .014f, 0xFF8D9598.toInt(), 0xFF171A1D.toInt())
        rim(canvas, .89f, .035f, 0xFF14181D.toInt(), 0xFF7B858A.toInt())
        rim(canvas, .856f, .024f, Color.BLACK, 0xFF242C33.toInt())
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(-.26f, -.3f, 1.5f,
            intArrayOf(0xFF242B2F.toInt(), 0xFF10171E.toInt(), 0xFF04080C.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, .841f, paint); paint.shader = null
        if (powered) drawFactory(canvas) else drawClosedHatch(canvas, random)
        // Machined sockets: recessed hex bolts, glint, screwdriver slots and rust halos.
        for (i in 0 until 10) {
            val a = i * (2f * PI.toFloat() / 10f) + .13f
            canvas.save(); canvas.translate(cos(a) * .947f, sin(a) * .947f)
            paint.style = Paint.Style.FILL; paint.color = 0xA0442D1D.toInt()
            canvas.drawCircle(0f, .006f, .039f, paint)
            paint.color = 0xFF070A0D.toInt(); canvas.drawCircle(0f, 0f, .029f, paint)
            path.rewind()
            for (side in 0..6) {
                val b = side * PI.toFloat() / 3f
                val x = cos(b) * .023f; val y = sin(b) * .023f
                if (side == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.shader = LinearGradient(-.02f, -.02f, .02f, .02f, 0xFF9AA3A6.toInt(), 0xFF252B30.toInt(), Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint); paint.shader = null
            paint.color = 0xFF12171A.toInt()
            rect.set(-.012f, -.0025f, .012f, .0025f)
            canvas.save(); canvas.rotate(i * 27f); canvas.drawRect(rect, paint); canvas.restore()
            canvas.restore()
        }
        drawBrackets(canvas)
        if (powered) {
            // Thin neon ring and soft rim bloom, baked into a transparent layer with no rectangular edges.
            paint.style = Paint.Style.STROKE
            for (i in 12 downTo 1) {
                paint.strokeWidth = .003f + i * .006f
                paint.color = ColorUtils.setAlphaComponent(cyan, 3 + (12 - i) / 2)
                canvas.drawCircle(0f, 0f, .838f, paint)
            }
            paint.strokeWidth = .009f; paint.color = cyan; canvas.drawCircle(0f, 0f, .838f, paint)
            paint.strokeWidth = .002f; paint.color = whiteHot; canvas.drawCircle(0f, 0f, .832f, paint)
        }
        // The power symbol is engraved steel when off, a glowing tube when on; the scene remains visible behind it.
        canvas.save(); canvas.translate(0f, -.045f)
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND; paint.style = Paint.Style.STROKE
        paint.strokeWidth = .064f; paint.color = 0xC0000000.toInt()
        canvas.save(); canvas.translate(.006f, .015f); canvas.drawPath(glyph, paint); canvas.restore()
        if (powered) {
            for (i in 8 downTo 1) {
                paint.strokeWidth = .039f + i * .014f
                paint.color = ColorUtils.setAlphaComponent(cyan, 5)
                canvas.drawPath(glyph, paint)
            }
            paint.strokeWidth = .035f; paint.color = cyan; canvas.drawPath(glyph, paint)
            paint.strokeWidth = .009f; paint.color = whiteHot; canvas.drawPath(glyph, paint)
        } else {
            paint.shader = LinearGradient(-.24f, -.32f, .2f, .24f,
                intArrayOf(0xFF424A4F.toInt(), 0xFF9DA7AC.toInt(), 0xFF555F67.toInt()), null, Shader.TileMode.CLAMP)
            paint.strokeWidth = .040f; canvas.drawPath(glyph, paint); paint.shader = null
            paint.strokeWidth = .006f; paint.color = 0x507E8D97; canvas.drawPath(glyph, paint)
        }
        canvas.restore()
        return bitmap
    }

    private fun drawCables(canvas: Canvas) {
        val random = Random(431)
        // Overlapping thick rubber sheaths with sinusoidal corrugations, not concentric line art.
        for (cable in 0 until 5) {
            val radius = 1.055f + cable * .037f
            path.rewind()
            for (step in 0..190) {
                val a = -.25f + step * .032f
                val rr = radius + .017f * sin(a * 3f + cable * 2f) + .008f * cos(a * 7f)
                val x = cos(a) * rr; val y = sin(a) * rr * (.97f + cable * .004f)
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = .043f; paint.color = 0xEE020509.toInt(); canvas.drawPath(path, paint)
            paint.strokeWidth = .029f; paint.color = 0xFF252C32.toInt(); canvas.drawPath(path, paint)
            paint.strokeWidth = .012f; paint.color = 0xFF3D454B.toInt()
            canvas.save(); canvas.translate(-.004f, -.006f); canvas.drawPath(path, paint); canvas.restore()
            for (step in 0 until 130) {
                val a = step * .047f + cable * .023f
                val rr = radius + .017f * sin(a * 3f + cable * 2f) + .008f * cos(a * 7f)
                val x = cos(a) * rr; val y = sin(a) * rr * (.97f + cable * .004f)
                val half = .015f + random.nextFloat() * .003f
                paint.strokeWidth = .004f; paint.color = 0xC0080B0E.toInt()
                canvas.drawLine(x - cos(a + .4f) * half, y - sin(a + .4f) * half,
                    x + cos(a + .4f) * half, y + sin(a + .4f) * half, paint)
            }
        }
    }

    private fun rim(canvas: Canvas, radius: Float, width: Float, top: Int, bottom: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width
        paint.shader = LinearGradient(-.6f, -.8f, .6f, .8f, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, radius, paint); paint.shader = null
    }

    private fun drawBrackets(canvas: Canvas) {
        // Four clamp plates physically tie the casting to the cable harness.
        for (i in 0 until 4) {
            canvas.save(); canvas.rotate(34f + i * 90f)
            rect.set(-.065f, -1.20f, .065f, -.99f)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(-.065f, 0f, .065f, 0f,
                intArrayOf(0xFF171D22.toInt(), 0xFF616A71.toInt(), 0xFF252D33.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, .018f, .018f, paint); paint.shader = null
            paint.color = 0xFF080C10.toInt(); canvas.drawCircle(0f, -1.05f, .020f, paint)
            paint.color = 0xFF899297.toInt(); canvas.drawCircle(-.003f, -1.053f, .010f, paint)
            canvas.restore()
        }
    }

    private fun drawClosedHatch(canvas: Canvas, random: Random) {
        canvas.save()
        path.rewind(); path.addCircle(0f, 0f, .83f, Path.Direction.CW); canvas.clipPath(path)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(-.7f, -.8f, .7f, .8f,
            intArrayOf(0xFF374044.toInt(), 0xFF1C252C.toInt(), 0xFF10171B.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, .83f, paint); paint.shader = null
        for (i in 0 until 2100) {
            val x = random.nextFloat() * 1.7f - .85f; val y = random.nextFloat() * 1.7f - .85f
            paint.color = if (i % 3 == 0) 0x204F5E66 else 0x30000000
            canvas.drawCircle(x, y, random.nextFloat() * .004f + .001f, paint)
        }
        // Horizontal hatch seam, reinforcement rails and chipped edges.
        paint.style = Paint.Style.STROKE; paint.strokeWidth = .009f; paint.color = 0xFF080D12.toInt()
        canvas.drawLine(-.84f, .12f, .84f, .12f, paint)
        paint.strokeWidth = .003f; paint.color = 0x605A646B
        canvas.drawLine(-.84f, .13f, .84f, .13f, paint)
        for (i in 0..1) {
            val y = -.43f + i * .86f
            paint.strokeWidth = .036f; paint.color = 0xFF0F161C.toInt(); canvas.drawLine(-.85f, y, .85f, y, paint)
            paint.strokeWidth = .008f; paint.color = 0x80525D64.toInt(); canvas.drawLine(-.85f, y - .014f, .85f, y - .014f, paint)
        }
        for (i in 0 until 70) {
            val x = random.nextFloat() * 1.6f - .8f; val y = random.nextFloat() * 1.5f - .75f
            paint.strokeWidth = .0016f; paint.color = 0x385F707A
            canvas.drawLine(x, y, x + .015f + random.nextFloat() * .09f, y - .005f, paint)
        }
        canvas.restore()
    }

    private fun drawFactory(canvas: Canvas) {
        canvas.save()
        path.rewind(); path.addCircle(0f, 0f, .827f, Path.Direction.CW); canvas.clipPath(path)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(.16f, .18f, 1f,
            intArrayOf(0xFF57432C.toInt(), 0xFF182D39.toInt(), 0xFF050B13.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, .84f, paint); paint.shader = null
        // Distant stacks: different heights and capped brick bands.
        val chimneys = floatArrayOf(-.48f, -.64f, .075f, -.14f, -.50f, .052f, .38f, -.69f, .087f, .58f, -.44f, .058f)
        for (i in chimneys.indices step 3) {
            val x = chimneys[i]; val y = chimneys[i + 1]; val w = chimneys[i + 2]
            paint.shader = LinearGradient(x, 0f, x + w, 0f,
                intArrayOf(0xFF19222A.toInt(), 0xFF59616A.toInt(), 0xFF1D252B.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(x, y, x + w, .35f, paint); paint.shader = null
            paint.color = 0xFF1B2026.toInt(); canvas.drawRect(x - .009f, y, x + w + .009f, y + .025f, paint)
            paint.strokeWidth = .002f; paint.style = Paint.Style.STROKE; paint.color = 0x654B3E36
            for (j in 0 until 12) canvas.drawLine(x, y + j * .045f, x + w, y + j * .045f, paint)
            paint.style = Paint.Style.FILL
        }
        // Pitched roofs and corrugated sheds; the factory spans the aperture rather than a small grid of dots.
        val buildings = floatArrayOf(-.78f, -.08f, .33f, -.43f, -.23f, .29f, -.12f, -.04f, .36f, .26f, -.14f, .30f, .57f, .04f, .25f)
        for (i in buildings.indices step 3) {
            val x = buildings[i]; val y = buildings[i + 1]; val width = buildings[i + 2]
            paint.shader = LinearGradient(x, y, x + width, .6f,
                intArrayOf(0xFF5D5445.toInt(), 0xFF333B40.toInt(), 0xFF1A252D.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(x, y, x + width, .58f, paint); paint.shader = null
            paint.color = 0xFF111A22.toInt()
            path.rewind(); path.moveTo(x - .01f, y); path.lineTo(x + width * .52f, y - .09f)
            path.lineTo(x + width + .01f, y); path.close(); canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = .003f; paint.color = 0x605C656B
            for (rib in 0 until 14) {
                val bx = x + rib * width / 14f
                canvas.drawLine(bx, y + .01f, bx, .58f, paint)
            }
            paint.style = Paint.Style.FILL
            for (col in 0 until 4) for (row in 0 until 4) {
                val wx = x + width * (.10f + col * .22f); val wy = y + .075f + row * .12f
                paint.color = 0xFF080E16.toInt(); canvas.drawRect(wx - .008f, wy - .008f, wx + .043f, wy + .069f, paint)
                val bright = (i + col * 7 + row * 3) % 6 != 0
                paint.color = if (bright) 0xFFFFB957.toInt() else 0xFF725333.toInt()
                canvas.drawRect(wx, wy, wx + .036f, wy + .057f, paint)
                paint.color = 0x90421D08.toInt(); canvas.drawRect(wx + .015f, wy, wx + .019f, wy + .057f, paint)
            }
        }
        // Pipes, a catwalk and foreground equipment frame the central symbol.
        paint.style = Paint.Style.STROKE; paint.strokeWidth = .024f; paint.color = 0xFF101B24.toInt()
        path.rewind(); path.moveTo(-.74f, .46f); path.lineTo(-.74f, -.34f); path.lineTo(-.35f, -.34f)
        canvas.drawPath(path, paint)
        paint.strokeWidth = .008f; paint.color = 0xFF758189.toInt(); canvas.drawPath(path, paint)
        paint.strokeWidth = .011f; paint.color = 0xFF121B22.toInt()
        for (i in 0 until 9) {
            val x = -.8f + i * .20f
            canvas.drawLine(x, .55f, x + .10f, .39f, paint)
            canvas.drawLine(x + .10f, .39f, x + .20f, .55f, paint)
        }
        canvas.drawLine(-.85f, .40f, .85f, .40f, paint)
        canvas.drawLine(-.85f, .55f, .85f, .55f, paint)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, .43f, 0f, .85f, Color.TRANSPARENT, 0xFF03070B.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(-.85f, .43f, .85f, .85f, paint); paint.shader = null
        canvas.restore()
    }

    private fun drawSmoke(canvas: Canvas, phase: Float) {
        canvas.save()
        path.rewind(); path.addCircle(0f, 0f, .827f, Path.Direction.CW); canvas.clipPath(path)
        paint.style = Paint.Style.FILL; paint.shader = smoke
        for (stack in 0 until 2) for (puff in 0 until 10) {
            val age = (phase * .17f + puff * .1f + stack * .31f) % 1f
            val x = (if (stack == 0) .42f else -.44f) - age * .30f + sin(age * 8f + stack) * .035f
            val y = (if (stack == 0) -.67f else -.62f) - age * .40f
            val radius = .045f + age * .15f
            canvas.save(); canvas.translate(x, y); canvas.scale(radius * 1.5f, radius)
            paint.alpha = ((1f - age) * 140 * light).toInt()
            canvas.drawCircle(0f, 0f, 1f, paint); canvas.restore()
        }
        paint.shader = null; paint.alpha = 255
        canvas.restore()
    }

    private fun drawElectricity(canvas: Canvas, phase: Float) {
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        val flash = (phase * 11).toInt()
        // Short-lived branching arcs follow the physical cable bundle instead of regular rotating polygons.
        for (arc in 0 until 4) {
            path.rewind()
            val start = arc * 1.57f + flash * .19f
            for (step in 0..18) {
                val angle = start + step * .029f
                val jitter = sin(step * 12.71f + flash * 3.17f + arc) * .027f
                val radius = 1.10f + jitter + sin(step * 5.2f + flash) * .011f
                val x = cos(angle) * radius; val y = sin(angle) * radius
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                if (step == 9) {
                    path.lineTo(x + cos(angle + .9f) * .12f, y + sin(angle + .9f) * .12f)
                    path.moveTo(x, y)
                }
            }
            for (stroke in 3 downTo 0) {
                paint.strokeWidth = .003f + stroke * .012f
                paint.color = ColorUtils.setAlphaComponent(if (stroke == 0) whiteHot else cyan,
                    ((if (stroke == 0) 230 else 20) * light).toInt())
                canvas.drawPath(path, paint)
            }
        }
        if (connecting) {
            rect.set(-.845f, -.845f, .845f, .845f)
            paint.color = cyan; paint.strokeWidth = .014f
            canvas.drawArc(rect, phase * 190f, 100f, false, paint)
        }
    }

    private fun drawCaption(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        val current = statusText.ifEmpty { idleText }
        if (current != caption) { caption = current; upperCaption = current.uppercase(java.util.Locale.ROOT) }
        val width = (if (textRoom > 0f) minOf(textRoom, bounds.width().toFloat()) else bounds.width().toFloat()) * .94f
        val top = cy + radius + 27f * density
        paint.typeface = if (connected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textSize = (if (connected) 18f else 14f) * density
        val label = if (connected) upperCaption else caption
        val measured = paint.measureText(label)
        if (measured > width) paint.textSize *= width / measured
        paint.color = if (connected) cyan else MobilePalette.muted
        canvas.drawText(label, cx, top, paint)
        if (connected && elapsedText.isNotEmpty()) {
            paint.textSize = 20f * density; paint.color = MobilePalette.text
            canvas.drawText(elapsedText, cx, top + 28f * density, paint)
        }
    }
}
