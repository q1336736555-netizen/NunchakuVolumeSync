package cn.local.mv

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.min

internal class TransportIcon(context:Context, private val icon:String, description:String, private val accent:Boolean=false, click:()->Unit):View(context){
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply {strokeWidth=1.5f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    var playing=false
        set(value){if(field!=value){field=value;invalidate()}}
    init {contentDescription=description;isFocusable=true;setOnClickListener{click()}}
    override fun onDraw(c:Canvas){
        val density=resources.displayMetrics.density;val k=min(density,min(width,height)/32f)
        c.save();c.translate(width/2f,height/2f);c.scale(k,k)
        pen.color=if(accent)pink else Color.WHITE;pen.style=Paint.Style.STROKE
        when(icon){
            "play","next","previous"->{
                if(icon=="play" && playing){
                    pen.style=Paint.Style.FILL
                    c.drawRoundRect(-7f,-10f,-3f,10f,1.6f,1.6f,pen);c.drawRoundRect(3f,-10f,7f,10f,1.6f,1.6f,pen)
                }else{
                    if(icon=="previous")c.scale(-1f,1f)
                    val p=Path().apply{moveTo(-7f,-8f);lineTo(6f,0f);lineTo(-7f,8f);close()}
                    c.drawPath(p,pen)
                    if(icon!="play")c.drawLine(9f,-8f,9f,8f,pen)
                }
            }
            "queue"->for(y in listOf(-6f,0f,6f)){c.drawCircle(-9f,y,.8f,pen);c.drawLine(-4f,y,9f,y,pen)}
            "full"->for(sx in listOf(-1f,1f))for(sy in listOf(-1f,1f)){
                c.drawLine(8*sx,8*sy,3*sx,8*sy,pen);c.drawLine(8*sx,8*sy,8*sx,3*sy,pen)
            }
        }
        c.restore()
    }
}
