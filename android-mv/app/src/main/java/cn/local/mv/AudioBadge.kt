package cn.local.mv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

/** Single touch target. The meter and the two Dolby faces have independent motion envelopes. */
internal class AudioBadge(context: Context) : View(context) {
    private val brush=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val word=BitmapFactory.decodeResource(resources,R.drawable.dolby_word)
    private var kind=AudioKind.STANDARD
    private var enabledTrack=false
    private var phase=1f
    private var pending: ValueAnimator?=null
    private val white=Color.rgb(244,244,246)
    init { isFocusable=true; isClickable=true;contentDescription="标准音质" }
    fun bind(format:AudioKind, active:Boolean, animate:Boolean=false) {
        if(kind==format && enabledTrack==active) return
        pending?.cancel(); kind=format; enabledTrack=active
        contentDescription=format.title + if(active) "，已开启" else "，已关闭"
        isSelected=active
        if(animate && format!=AudioKind.STANDARD) {
            phase=0f
            pending=ValueAnimator.ofFloat(0f,1f).apply {
                duration=when(format) { AudioKind.DOLBY -> if(active) 1550L else 700L; else -> if(active) 850L else 660L }
                interpolator=LinearInterpolator()
                addUpdateListener { phase=it.animatedValue as Float; invalidate() };start()
            }
        } else { phase=1f;invalidate() }
    }
    private fun smooth(v:Float):Float { val t=v.coerceIn(0f,1f);return t*t*(3-2*t) }
    private fun hit(t:Float,at:Float,attack:Float,tail:Float):Float {
        val v=t-at
        return when {v<0 || v>attack+tail -> 0f; v<attack -> 1-(1-v/attack).pow(2); else -> (1-(v-attack)/tail).pow(1.7f)}
    }
    private fun tint(amount:Float):Int {
        val a=amount.coerceIn(0f,1f)
        return Color.rgb((244+7*a).roundToInt(),(244-130*a).roundToInt(),(246-93*a).roundToInt())
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val s=min(resources.displayMetrics.density,width/99f)
        canvas.save();canvas.translate(width/2f,height/2f);canvas.scale(s,s)
        val front=if(enabledTrack)smooth((phase-.13f)/.6f) else 1-smooth((phase-.13f)/.6f)
        brush.style=Paint.Style.FILL;brush.color=white
        if(kind==AudioKind.DOLBY) drawDolby(canvas,front) else drawTextBadge(canvas,front)
        canvas.restore()
    }
    private fun drawTextBadge(c:Canvas,front:Float) {
        val premium=kind==AudioKind.HIRES
        val label=if(premium) "Hi-Res无损" else "标准音质"
        brush.typeface=Typeface.create("sans-serif",Typeface.NORMAL);brush.textSize=15f
        val w=brush.measureText(label);val x=if(premium) -w/2+7 else -w/2
        val t=phase*(if(enabledTrack) .85f else .66f)
        if(premium && phase<1) {
            val timings=if(enabledTrack) floatArrayOf(.065f,.33f,.505f,.655f) else floatArrayOf(.045f,.245f,.409f)
            for(i in 0..4) {
                val side=(2-abs(i-2))*.12f+.72f
                var strength=0f
                for(j in timings.indices) {
                    val offset=(if(enabledTrack)i else 4-i)*.012f
                    val focus=intArrayOf(2,3,1,2)[j]
                    strength+=hit(t,timings[j]+offset,.05f,.19f)*(.95f-.22f*abs(i-focus))
                }
                val height=15f*side*strength
                if(height>.3f) {
                    brush.color=pink;val bx=x-17+i*2.9f
                    c.drawRoundRect(bx,-height/2,bx+1.55f,height/2,1f,1f,brush)
                }
            }
        }
        val scale=1-.085f*hit(phase,0f,.12f,.16f)+.055f*hit(phase,.22f,.12f,.25f)
        c.save();c.scale(scale,scale,x+w/2,0f)
        brush.color=if(premium) white else 0xffc1c0c7.toInt()
        val baseline=-(brush.ascent()+brush.descent())/2
        c.drawText(label,x,baseline,brush)
        if(premium && front>0) {
            c.save();c.clipRect(x-1,-20f,x+(w+1)*front,20f);brush.color=pink;c.drawText(label,x,baseline,brush);c.restore()
        }
        c.restore()
    }
    private fun drawDolby(c:Canvas,front:Float) {
        val t=phase*(if(enabledTrack)1.55f else .70f)
        for(i in 0..1) {
            val p=if(enabledTrack) hit(t,.1f+.021f*i,.052f,.31f)+hit(t,.60f,.032f,.14f)*.2f+
                hit(t,.85f,.042f,.18f)*(if(i==1).83f else .14f)+hit(t,1.10f,.053f,.33f)*(if(i==0)1.06f else .61f)
            else .62f*hit(t,.05f+i*.018f,.047f,.17f)+.36f*hit(t,.30f,.047f,.20f)
            val cx=-39f+i*19f+(if(i==0)-4.5f else 1.5f)*p
            val rect=RectF(cx-8, -6.6f, cx+8, 6.6f)
            c.save();c.scale(1+.15f*p,1+.34f*p,cx,0f)
            brush.color=white
            val start=if(i==0)-90f else 90f
            c.drawArc(rect,start,180f,false,brush)
            c.save()
            if(i==0)c.clipRect(cx+8-8*front,-8f,cx+8,8f) else c.clipRect(cx-8,-8f,cx-8+8*front,8f)
            brush.color=pink;c.drawArc(rect,start,180f,false,brush);c.restore();c.restore()
        }
        val w=40f;val h=w*word.height/word.width
        val r=RectF(-18f,-h/2,-18f+w,h/2)
        c.save()
        val q=t/(if(enabledTrack).69f else .57f)
        val scale=1-.09f*hit(q,0f,.18f,.17f)+.12f*hit(q,.28f,.15f,.28f)
        c.scale(scale,scale,r.centerX(),0f)
        brush.color=Color.WHITE;brush.colorFilter=null;c.drawBitmap(word,null,r,brush)
        c.clipRect(r.left-1,r.top,r.left+w*front,r.bottom)
        brush.colorFilter=PorterDuffColorFilter(pink,PorterDuff.Mode.SRC_IN);c.drawBitmap(word,null,r,brush)
        brush.colorFilter=null;c.restore()
    }
    override fun onDetachedFromWindow() { pending?.cancel();phase=1f;super.onDetachedFromWindow() }
}
