package app.rawline.mobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.*
import kotlin.random.Random

/** Native interpretation of rawline_html5_canvas.html; HTML controls and simulated VPN are not embedded. */
class CourtyardOrbArtwork(context: Context, private val idleText: String) : MobileOrbArtwork(context, idleText) {
    private val density = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val clip = Path().apply { addCircle(0f, 0f, 102f, Path.Direction.CW) }
    private val rect = RectF()
    private val glyph = Path().apply {
        addArc(RectF(-27f, -29f, 27f, 25f), -58f, 296f)
        moveTo(0f, -35f); lineTo(0f, -5f)
    }
    private val random = Random(1337)
    private val graffitiFont = Typeface.create("sans-serif-condensed",Typeface.BOLD)
    private val arcPower = PowerButtonStyle.arcs
    private val furnacePower = PowerButtonStyle.furnace
    private val snowPower = PowerButtonStyle.snow
    private val walls = arrayOf(
        Wall(-54f, -34f, 58f, -24f, -20f, 54f, 6, 3, false, 7f),
        Wall(21f, -18f, 52f, 48f, -31f, 55f, 5, 3, false, -6f),
        Wall(-102f, -52f, 66f, -50f, -26f, 58f, 6, 4, true, 11f),
        Wall(46f, -25f, 55f, 102f, -54f, 64f, 6, 4, true, -12f)
    )
    private data class Wall(val x0: Float, val top0: Float, val bottom0: Float, val x1: Float,
        val top1: Float, val bottom1: Float, val rows: Int, val cols: Int, val balconies: Boolean, val side: Float) {
        fun x(u: Float) = x0 + (x1 - x0) * u
        fun y(u: Float, v: Float): Float {
            val top = top0 + (top1 - top0) * u
            return top + (bottom0 + (bottom1 - bottom0) * u - top) * v
        }
    }
    private data class Window(val wall: Wall, val row: Int, val col: Int, val threshold: Float,
        val idle: Boolean, val tv: Boolean, val warmth: Float, val phase: Float)
    private val windows = walls.flatMapIndexed { index, wall ->
        (0 until wall.rows).flatMap { row -> (0 until wall.cols).map { col ->
            Window(wall, row, col, if (random.nextFloat() < .14f) 9.9f else .05f + random.nextFloat() * .82f,
                (index == 2 && row == 2 && col == 1) || (index == 3 && row == 3 && col == 2),
                index == 0 && row == 1 && col == 1, random.nextFloat(), random.nextFloat() * 6.28f)
        } }
    }
    // x1,y1,x2,y2,sag,phase,speed: the same converging courtyard wires as the prototype.
    private val wires = arrayOf(
        floatArrayOf(-95f,-55f,-22f,-34f,14f,.2f,.9f), floatArrayOf(-88f,-50f,52f,-32f,22f,1.1f,1.1f),
        floatArrayOf(-62f,-36f,85f,-48f,26f,2.3f,.8f), floatArrayOf(25f,-22f,96f,-52f,13f,3.4f,1.2f),
        floatArrayOf(-92f,-32f,-24f,-6f,16f,.7f,1.3f), floatArrayOf(-76f,-14f,-20f,12f,14f,1.9f,1f),
        floatArrayOf(88f,-35f,24f,-6f,16f,2.8f,1.25f), floatArrayOf(78f,-12f,21f,14f,15f,3.9f,.95f),
        floatArrayOf(-16f,22f,-55f,54f,8f,1.5f,1.4f), floatArrayOf(-6f,26f,-24f,52f,6f,2.2f,1.15f),
        floatArrayOf(8f,26f,28f,49f,6f,.4f,1.35f), floatArrayOf(18f,21f,56f,46f,9f,3.1f,1.05f)
    )
    private val stacks = arrayOf(floatArrayOf(-30f,-58f,5.5f,9.5f), floatArrayOf(14f,-62f,6f,10.5f), floatArrayOf(31f,-47f,4.2f,7.5f))
    private val flakes = Array(32) { floatArrayOf(random.nextFloat()*204-102,random.nextFloat()*204-102,
        .7f+random.nextFloat()*1.4f,8+random.nextFloat()*12,14+random.nextFloat()*18,.25f+random.nextFloat()*.5f) }
    private val puffs = Array(34) { i -> floatArrayOf((i%3).toFloat(),i/34f,.14f+random.nextFloat()*.08f,
        10+random.nextFloat()*14,(random.nextFloat()-.5f)*10,(random.nextFloat()-.5f)*6) }
    private var staticLayer: Bitmap? = null
    private var sizeKey = 0
    private var sky: Shader? = null
    private var furnace: Shader? = null
    private var smoke: Shader? = null
    private var vignette: Shader? = null
    private var aura: Shader? = null
    private var lastTime = 0L
    private var phase = 0f
    private var progress = 0f
    private var caption = ""
    private var captionUpper = ""

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val disc = if (discSize > 0) discSize else min(bounds.width(),bounds.height())
        val radius = MobileOrbArtwork.radius(disc)
        cache(radius)
        val now = SystemClock.uptimeMillis()
        val dt = if (lastTime == 0L) .016f else ((now-lastTime)/1000f).coerceIn(0f,.05f)
        lastTime = now
        val moving = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        val target = if (connected) 1f else if (connecting) .52f else 0f
        progress = if (moving) progress + (target-progress)*(1-exp(-dt*4.5f)) else target
        if (abs(progress-target)<.003f) progress=target
        if (moving) phase += dt
        val cx=bounds.exactCenterX(); val cy=bounds.top+MobileOrbArtwork.centerY(disc)
        canvas.save(); canvas.translate(cx,cy); canvas.scale(radius/114f*pressScale,radius/114f*pressScale)
        p.reset(); p.isAntiAlias=true; p.isFilterBitmap=true
        if (progress>.01f) {
            p.shader=aura; p.alpha=(progress*255).toInt(); canvas.drawCircle(0f,0f,161f,p)
            p.shader=null; p.alpha=255
            p.style=Paint.Style.STROKE; p.strokeWidth=1.1f
            for (i in 0 until 3) {
                val age=(phase*.38f+i/3f)%1f
                p.color=Color.argb(((1-age)*progress*45).toInt(),56,189,248)
                canvas.drawCircle(0f,0f,124+age*28,p)
            }
        }
        canvas.save(); canvas.clipPath(clip)
        p.style=Paint.Style.FILL; p.shader=sky; canvas.drawCircle(0f,0f,102f,p); p.shader=null
        p.shader=furnace; p.alpha=((.08f+progress*.92f)*furnacePower*210).toInt().coerceIn(0,255)
        canvas.drawCircle(4f,8f,88f,p); p.shader=null; p.alpha=255
        drawPlant(canvas)
        drawSmoke(canvas,false)
        staticLayer?.let { rect.set(-102f,-102f,102f,102f); canvas.drawBitmap(it,null,rect,p) }
        drawWindows(canvas)
        drawParapet(canvas)
        drawWires(canvas)
        drawPower(canvas)
        if (snowPower>.01f) {
            p.style=Paint.Style.FILL
            for (f in flakes) {
                if (moving) { f[0]=wrap(f[0]+f[3]*dt*snowPower); f[1]=wrap(f[1]+f[4]*dt*snowPower) }
                p.color=Color.argb((f[5]*min(1f,snowPower)*130).toInt(),203,213,225)
                canvas.drawCircle(f[0],f[1],f[2],p)
            }
        }
        p.style=Paint.Style.FILL; p.alpha=255; p.shader=vignette; canvas.drawCircle(0f,0f,102f,p); p.shader=null
        canvas.restore()
        drawRim(canvas)
        drawSmoke(canvas,true)
        if (progress>.03f && arcPower>.01f) drawLightning(canvas)
        if (focused) { p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=MobilePalette.text;canvas.drawCircle(0f,0f,135f,p) }
        canvas.restore()
        drawCaption(canvas,cx,cy,radius)
        val host=callback as? View
        if (moving && !MobileOrbArtwork.frozen && host!=null && host.isShown && host.hasWindowFocus()) host.postInvalidateOnAnimation()
    }

    private fun wrap(v: Float) = if (v>102) v-204 else v
    private fun blend(a: Int,b: Int,t: Float)=ColorUtils.blendARGB(a,b,t.coerceIn(0f,1f))
    private fun poly(canvas: Canvas,w: Wall,u0: Float,v0: Float,u1: Float,v1: Float) {
        path.rewind();path.moveTo(w.x(u0),w.y(u0,v0));path.lineTo(w.x(u1),w.y(u1,v0))
        path.lineTo(w.x(u1),w.y(u1,v1));path.lineTo(w.x(u0),w.y(u0,v1));path.close();canvas.drawPath(path,p)
    }
    private fun cache(radius: Float) {
        val size=(radius*204/114).toInt().coerceIn(256,1024)
        if (sizeKey==size) return
        sizeKey=size
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap);c.translate(size/2f,size/2f);c.scale(size/204f,size/204f)
        for (w in walls) {
            val edge=if(w.side>0) 1f else 0f
            p.style=Paint.Style.FILL;p.color=0xFF0D141F.toInt()
            path.rewind();path.moveTo(w.x(edge),w.y(edge,0f));path.lineTo(w.x(edge)+w.side,w.y(edge,0f)+4)
            path.lineTo(w.x(edge)+w.side,w.y(edge,1f));path.lineTo(w.x(edge),w.y(edge,1f));path.close();c.drawPath(path,p)
            p.shader=LinearGradient(w.x0,w.top0,w.x1,w.bottom1,0xFF25303D.toInt(),0xFF121A26.toInt(),Shader.TileMode.CLAMP)
            poly(c,w,0f,0f,1f,1f);p.shader=null
            p.style=Paint.Style.STROKE;p.strokeWidth=.9f;p.color=0xB006090E.toInt()
            for (row in 1 until w.rows) {val v=row.toFloat()/w.rows;c.drawLine(w.x(0f),w.y(0f,v),w.x(1f),w.y(1f,v),p)}
            for (col in 1 until w.cols) {val u=col.toFloat()/w.cols;c.drawLine(w.x(u),w.y(u,0f),w.x(u),w.y(u,1f),p)}
            p.strokeWidth=1.6f;p.color=0xFF05070B.toInt();poly(c,w,0f,0f,1f,1f)
            if (w.balconies) {
                val x=w.x(.55f);val y=w.y(.55f,0f)
                p.strokeWidth=1.2f;c.drawLine(x,y,x,y-12,p);c.drawLine(x-5,y-9,x+5,y-7,p)
                for(row in 1 until w.rows) { val v=row.toFloat()/w.rows;poly(c,w,.08f,v,.36f,(v+.025f).coerceAtMost(1f)) }
            }
        }
        val noise=Random(901);p.style=Paint.Style.FILL
        for(i in 0 until 1700) {p.color=if(i%2==0) 0x103E4C61 else 0x18000000;c.drawCircle(noise.nextFloat()*204-102,noise.nextFloat()*204-102,.35f,p)}
        staticLayer=bitmap
        sky=LinearGradient(0f,-102f,0f,102f,intArrayOf(0xFF0A0E16.toInt(),0xFF182436.toInt(),0xFF0D121A.toInt()),null,Shader.TileMode.CLAMP)
        furnace=RadialGradient(4f,8f,88f,intArrayOf(0xDAFFC85A.toInt(),0xA6FF7319.toInt(),0x40D2370A,Color.TRANSPARENT),floatArrayOf(0f,.32f,.68f,1f),Shader.TileMode.CLAMP)
        smoke=RadialGradient(0f,0f,1f,intArrayOf(0xDD536070.toInt(),0x88404B5A.toInt(),Color.TRANSPARENT),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
        vignette=RadialGradient(0f,0f,102f,intArrayOf(Color.TRANSPARENT,Color.TRANSPARENT,0xAA04070C.toInt()),floatArrayOf(0f,.68f,1f),Shader.TileMode.CLAMP)
        aura=RadialGradient(0f,0f,161f,intArrayOf(Color.TRANSPARENT,0x2638BDF8,Color.TRANSPARENT),floatArrayOf(0f,.70f,1f),Shader.TileMode.CLAMP)
    }

    private fun drawPlant(c: Canvas) {
        p.style=Paint.Style.FILL;p.color=0xFF0E131B.toInt()
        c.drawRect(-42f,4f,38f,28f,p);c.drawRect(-15f,-2f,38f,28f,p)
        for ((index,ch) in stacks.withIndex()) {
            val x=ch[0];val y=ch[1];val tw=ch[2];val bw=ch[3]
            path.rewind();path.moveTo(x-tw,y);path.lineTo(x+tw,y);path.lineTo(x+bw,18f);path.lineTo(x-bw,18f);path.close()
            p.color=blend(0xFF26303D.toInt(),0xFF6E4940.toInt(),progress*.6f);c.drawPath(path,p)
            c.save();c.clipPath(path)
            for (band in 0..2) {p.color=if(band%2==0) 0xBB993730.toInt() else 0x887D8B9A.toInt();c.drawRect(x-bw-2,y+5+band*9,x+bw+2,y+9.5f+band*9,p)}
            c.restore();p.style=Paint.Style.STROKE;p.strokeWidth=1.3f;p.color=0xFF06080D.toInt();c.drawPath(path,p);p.style=Paint.Style.FILL
            val blink=.25f+(.5f+.5f*sin(phase*3.2f+index*1.9f))*.75f
            p.color=Color.argb((blink*55).toInt(),255,60,40);c.drawCircle(x,y+1,4.5f,p)
            p.color=Color.argb((blink*220).toInt(),255,110,90);c.drawCircle(x,y+1,1.4f,p)
        }
    }
    private fun drawWindows(c: Canvas) {
        for(w in windows) {
            val tv=w.tv && progress<.3f
            val amount=when {progress>w.threshold -> ((progress-w.threshold)/.16f).coerceIn(0f,1f)
                w.idle -> .28f+.05f*sin(phase*2+w.phase)
                tv -> .35f+.2f*sin(phase*7+w.phase)
                else -> 0f}
            val wall=w.wall;val u0=(w.col+.22f)/wall.cols;val u1=(w.col+.78f)/wall.cols
            val v0=(w.row+.20f)/wall.rows;val v1=(w.row+.76f)/wall.rows
            p.style=Paint.Style.FILL
            p.color=if(amount>.02f) {if(tv) Color.argb((amount*180).toInt(),56,189,248)
                else Color.argb((64+amount*191).toInt(),255,(145+w.warmth*70).toInt(),(45+w.warmth*70).toInt())} else 0xFF0C1119.toInt()
            poly(c,wall,u0,v0,u1,v1)
            p.style=Paint.Style.STROKE;p.color=0xFF06080D.toInt();p.strokeWidth=.9f;poly(c,wall,u0,v0,u1,v1)
            val u=(u0+u1)*.5f;c.drawLine(wall.x(u),wall.y(u,v0),wall.x(u),wall.y(u,v1),p)
        }
    }
    private fun drawParapet(c: Canvas) {
        c.save();c.translate(0f,62f);c.rotate(-6.3f)
        p.style=Paint.Style.FILL;p.color=0xFF182331.toInt();c.drawRect(-120f,-16f,120f,52f,p)
        p.color=blend(0xFF2E3846.toInt(),0xFF3D4F63.toInt(),progress);c.drawRect(-120f,-19f,120f,-14.5f,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1.4f;p.color=0xFF06090F.toInt();c.drawLine(-120f,22f,120f,22f,p)
        for(i in 0..8) {val x=-64f+i*16;p.color=blend(0x88505F72.toInt(),0xCC22D3EE.toInt(),progress);c.drawLine(x,4f,x,12f+(i*7%13),p)}
        p.typeface=graffitiFont;p.textAlign=Paint.Align.CENTER;p.textSize=18.5f
        p.style=Paint.Style.STROKE;p.strokeWidth=2.6f;p.color=0xFF070A10.toInt();c.drawText("МОИ БРАТИКИ",0f,6f,p)
        p.style=Paint.Style.FILL;p.color=blend(0xFF4A5666.toInt(),0xFFA5F3FC.toInt(),progress);c.drawText("МОИ БРАТИКИ",0f,6f,p)
        c.restore()
    }
    private fun drawWires(c: Canvas) {
        p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND
        for(w in wires) {
            val mx=(w[0]+w[2])*.5f;val my=max(w[1],w[3])+w[4]+sin(phase*1.9f+w[5])*2.4f
            path.rewind();path.moveTo(w[0],w[1]);path.quadTo(mx,my,w[2],w[3])
            p.color=0xFF05080D.toInt();p.strokeWidth=2.1f;c.drawPath(path,p)
            p.color=blend(0xFF1C2430.toInt(),0xFF2B3E52.toInt(),progress);p.strokeWidth=.9f;c.drawPath(path,p)
            if(progress>.05f) {
                val t=(phase*.45f*w[6]+w[5]*.3f)%1;val inv=1-t
                val x=inv*inv*w[0]+2*inv*t*mx+t*t*w[2];val y=inv*inv*w[1]+2*inv*t*my+t*t*w[3]
                p.style=Paint.Style.FILL;p.color=Color.argb((progress*110).toInt(),56,189,248);c.drawCircle(x,y,3.8f,p)
                p.color=Color.argb((progress*255).toInt(),224,242,254);c.drawCircle(x,y,1.2f,p);p.style=Paint.Style.STROKE
            }
        }
    }
    private fun drawPower(c: Canvas) {
        p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
        p.strokeWidth=8.5f;p.color=0xFF070A10.toInt();c.drawPath(glyph,p)
        p.strokeWidth=4.5f;p.color=0xFF293240.toInt();c.drawPath(glyph,p)
        if(progress>.02f) {
            for(i in 3 downTo 1) {p.strokeWidth=5f+i*3;p.color=Color.argb((progress*18).toInt(),6,182,212);c.drawPath(glyph,p)}
            p.strokeWidth=5.2f;p.color=Color.argb((progress*255).toInt(),224,242,254);c.drawPath(glyph,p)
        }
    }
    private fun drawSmoke(c: Canvas,outside: Boolean) {
        p.style=Paint.Style.FILL
        for(f in puffs) {
            val age=(f[1]+phase*f[2]*(.65f+progress*.85f))%1
            if(outside && age<.32f || !outside && age>=.55f) continue
            val ch=stacks[f[0].toInt()];val x=ch[0]+age*76+sin(phase*1.4f+age*5)*4+f[4]*age
            val y=ch[1]-age*68+f[5]*age;val radius=(6+age*f[3])*(.75f+progress*.35f)
            val visibility=if(outside) progress.pow(1.2f) else .28f+progress*.72f
            c.save();c.translate(x,y);c.scale(radius,radius)
            p.shader=smoke;p.alpha=(sin(age*PI.toFloat())*visibility*145).toInt().coerceIn(0,255)
            c.drawCircle(0f,0f,1f,p);p.shader=null;p.alpha=255
            if(progress*furnacePower>.35f && age<.55f) {p.color=Color.argb(((1-age)*progress*90).toInt(),255,140,45);c.drawCircle(0f,.3f,.40f,p)}
            c.restore()
        }
    }
    private fun drawRim(c: Canvas) {
        // Alternating front/back cable loops cross the casting as in the HTML design.
        for(front in 0..1) {
            for(i in 0 until 18) {
                if(i%2!=front) continue
                val a=i*2*PI.toFloat()/18-.22f;val b=a+.65f+(i%3)*.07f;val m=(a+b)*.5f
                path.rewind();path.moveTo(cos(a)*109,sin(a)*109);path.quadTo(cos(m)*136,sin(m)*136,cos(b)*109,sin(b)*109)
                p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND;p.strokeWidth=7f;p.color=0xFF05070B.toInt();c.drawPath(path,p)
                p.strokeWidth=4.5f;p.color=blend(0xFF1D2631.toInt(),0xFF2E4760.toInt(),progress*.5f);c.drawPath(path,p)
                p.strokeWidth=.8f;p.color=0x50637589;c.drawPath(path,p)
            }
            if(front==0) {
                p.strokeWidth=20f;p.color=blend(0xFF283342.toInt(),0xFF456681.toInt(),progress*.7f);c.drawCircle(0f,0f,112f,p)
                p.strokeWidth=2f;p.color=0xFF05070C.toInt();c.drawCircle(0f,0f,102f,p);c.drawCircle(0f,0f,122f,p)
                for(i in 0 until 12) {
                    val a=i*PI.toFloat()/6-PI.toFloat()/2;val x=cos(a)*113;val y=sin(a)*113
                    p.style=Paint.Style.FILL;p.color=0xFF070A0F.toInt();c.drawCircle(x+.8f,y+1,3.3f,p)
                    p.color=blend(0xFF3F4C5C.toInt(),0xFF7DD3FC.toInt(),progress*.65f);c.drawCircle(x,y,2.7f,p)
                }
            }
        }
    }
    private fun drawLightning(c: Canvas) {
        val power=((progress*arcPower).coerceAtMost(1.6f))
        val tick=(phase*16).toInt()
        p.style=Paint.Style.STROKE;p.strokeJoin=Paint.Join.ROUND
        for(i in 0 until 7) {
            val start=i*2*PI.toFloat()/7+phase*.35f*(if(i%2==0)1 else -1)
            path.rewind()
            for(j in 0..12) {
                val u=j/12f;val a=start+u*.78f
                val radius=117+sin(j*19.17f+tick*7.31f+i)*9*sin(u*PI.toFloat())
                val x=cos(a)*radius;val y=sin(a)*radius
                if(j==0)path.moveTo(x,y) else path.lineTo(x,y)
                if(j==6) {path.lineTo(x+cos(a+.6f)*11,y+sin(a+.6f)*11);path.moveTo(x,y)}
            }
            p.strokeWidth=6f;p.color=Color.argb((power*22).toInt().coerceAtMost(255),14,165,233);c.drawPath(path,p)
            p.strokeWidth=1.6f;p.color=Color.argb((power*225).toInt().coerceAtMost(255),186,230,253);c.drawPath(path,p)
        }
    }
    private fun drawCaption(c: Canvas,cx: Float,cy: Float,radius: Float) {
        p.reset();p.isAntiAlias=true;p.textAlign=Paint.Align.CENTER
        val current=statusText.ifEmpty {idleText}
        if(current!=caption) {caption=current;captionUpper=current.uppercase(java.util.Locale.ROOT)}
        p.typeface=if(connected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.textSize=(if(connected)18 else 14)*density
        val label=if(connected)captionUpper else caption
        val width=bounds.width()*.94f;val measured=p.measureText(label)
        if(measured>width)p.textSize*=width/measured
        p.color=if(connected)0xFF4ADE80.toInt() else MobilePalette.muted
        val top=cy+radius+27*density;c.drawText(label,cx,top,p)
        if(connected) {
            p.textSize=12*density;p.color=0xFF38BDF8.toInt();c.drawText("Братики онлайн!",cx,top+19*density,p)
            p.textSize=14*density;p.color=MobilePalette.text;c.drawText(elapsedText,cx,top+39*density,p)
        }
    }
}
