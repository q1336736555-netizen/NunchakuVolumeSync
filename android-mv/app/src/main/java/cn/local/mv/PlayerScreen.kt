package cn.local.mv

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

/** Portrait and landscape share the ExoPlayer owned by the activity. Panels never recreate it. */
internal class PlayerScreen(private val host: MainActivity, val wide: Boolean) {
    private val ctx=host
    val frame=FrameLayout(ctx)
    val video=PlayerView(ctx).apply { useController=false;setShutterBackgroundColor(Color.BLACK) }
    private val stage=FrameLayout(ctx)
    private val chrome=FrameLayout(ctx)
    private var drawer:FrameLayout?=null
    private var panel=""
    private var lastDetails=""
    private val title=ctx.txt("",if(wide) 16f else 22f).line()
    private val author=ctx.txt("",12f,muted).line()
    private val status=ctx.txt("",13f)
    private val data=ctx.txt("",12f,muted)
    private val progress=SeekBar(ctx)
    private val elapsed=ctx.txt("",12f)
    private val play=TransportIcon(ctx,"play","播放或暂停",!wide) { host.playPause() }
    private val position=ctx.txt("",14f)
    private val speed=ctx.action("倍速") { open("倍速") }
    private val quality=ctx.action("清晰度") { open("清晰度") }
    private val badge=AudioBadge(ctx)
    private var dragging=false
    private var queueCardIndex=-1
    private var carousel:HorizontalScrollView?=null
    private val hide=Runnable { if(wide && drawer==null && host.player?.isPlaying==true && !dragging) chrome.visibility=View.GONE }
    private fun dp(i:Int)=ctx.dp(i)
    private fun size(w:Int,h:Int)=FrameLayout.LayoutParams(if(w<0)w else dp(w),if(h<0)h else dp(h))
    private fun at(w:Int,h:Int,g:Int)=size(w,h).apply { gravity=g }
    private fun weight(v:Float)=LinearLayout.LayoutParams(0,-2,v)
    private fun arrow(glyph:String,desc:String,f:()->Unit)=ctx.action(glyph,desc,f).apply { textSize=25f }
    init {
        frame.setBackgroundColor(ink);stage.setBackgroundColor(Color.BLACK)
        stage.addView(video,size(-1,-1));video.setOnClickListener { if(wide) {
            chrome.visibility=if(chrome.visibility==View.VISIBLE) View.GONE else View.VISIBLE;scheduleHide()
        } }
        progress.max=1000;progress.progressTintList=ColorStateList.valueOf(pink)
        progress.thumbTintList=ColorStateList.valueOf(pink)
        progress.progressBackgroundTintList=ColorStateList.valueOf(0x66ffffff)
        progress.contentDescription="播放进度"
        progress.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s:SeekBar) { dragging=true;frame.removeCallbacks(hide) }
            override fun onStopTrackingTouch(s:SeekBar) {
                val player=host.player;val duration=player?.duration ?: 0
                if(duration>0) player?.seekTo(duration*s.progress/1000)
                dragging=false;scheduleHide()
            }
            override fun onProgressChanged(s:SeekBar,v:Int,user:Boolean) {
                val duration=host.player?.duration ?: 0
                if(user && duration>0)elapsed.text=clockTime(duration*v/1000)+" / "+clockTime(duration)
            }
        })
        badge.setOnClickListener { host.toggleAudio() }
        if(wide) landscape() else portrait()
        status.gravity=Gravity.CENTER;status.pad(12,5,12,5);status.background=ctx.round(0xc418181b.toInt(),7)
        status.setOnClickListener { host.retry() }
        stage.addView(status,at(-2,-2,Gravity.CENTER))
    }
    private fun landscape(){
        frame.addView(stage,size(-1,-1));stage.addView(chrome,size(-1,-1))
        val top=ctx.bar().apply {
            pad(10,0,14,0)
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xac000000.toInt(),Color.TRANSPARENT))
        }
        top.addView(arrow("‹","退出全屏"){host.fullscreen(false)},dp(44),-1)
        top.addView(title,weight(1f));top.addView(position);top.addView(arrow("‹","音频详情"){open("音频详情")},dp(48),-1)
        chrome.addView(top,at(-1,54,Gravity.TOP))
        val bottom=ctx.column().apply {
            pad(14,6,14,0);background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xc7000000.toInt()))
        }
        bottom.addView(progress,-1,dp(26))
        val bar=ctx.bar()
        bar.addView(play,dp(39),dp(44))
        bar.addView(TransportIcon(ctx,"next","下一首"){host.move(1)},dp(43),dp(44))
        bar.addView(elapsed,weight(1f))
        bar.addView(ctx.action("选集"){open("选集")});bar.addView(speed);bar.addView(badge,dp(102),dp(44));bar.addView(quality)
        bottom.addView(bar);chrome.addView(bottom,at(-1,-2,Gravity.BOTTOM))
    }
    private fun portrait(){
        val scroll=ScrollView(ctx).apply { isFillViewport=true }
        val page=ctx.column().apply { pad(16,0,16,16) };scroll.addView(page);frame.addView(scroll)
        val top=ctx.bar()
        top.addView(arrow("‹","返回收藏夹"){host.leavePlayer()},dp(36),dp(46))
        top.addView(ctx.txt(host.folderName,16f).line(),weight(1f));top.addView(position)
        page.addView(top)
        val tabs=ctx.bar().apply{gravity=Gravity.CENTER}
        val mv=ctx.column().apply{gravity=Gravity.CENTER}
        mv.addView(ctx.txt("MV",15f).apply {gravity=Gravity.CENTER},dp(48),dp(33))
        mv.addView(View(ctx).apply{setBackgroundColor(pink)},dp(27),dp(2));tabs.addView(mv)
        tabs.addView(ctx.txt("  │  ",11f,muted))
        tabs.addView(ctx.action("音频详情"){open("音频详情")});page.addView(tabs,-1,dp(42))
        val w=ctx.resources.displayMetrics.widthPixels-2*dp(16)
        stage.background=ctx.round(Color.BLACK,12);stage.clipToOutline=true
        page.addView(stage,-1,w*9/16)
        val full=TransportIcon(ctx,"full","全屏播放"){host.fullscreen(true)}.apply { background=ctx.round(0x79000000,8) }
        stage.addView(full,at(44,40,Gravity.BOTTOM or Gravity.RIGHT))
        title.gravity=Gravity.CENTER;title.pad(0,17,0,0);page.addView(title)
        author.gravity=Gravity.CENTER;author.pad(0,6,0,2);page.addView(author)
        val detail=ctx.bar().apply { gravity=Gravity.CENTER;setOnClickListener { open("音频详情") } }
        data.gravity=Gravity.CENTER;detail.addView(data);page.addView(detail,-1,dp(33))
        page.addView(progress,-1,dp(24));elapsed.pad(4,0,0,0);page.addView(elapsed)
        val transport=ctx.bar().apply { gravity=Gravity.CENTER }
        transport.addView(TransportIcon(ctx,"previous","上一首"){host.move(-1)},dp(53),dp(48));transport.addView(play,dp(64),dp(48))
        transport.addView(TransportIcon(ctx,"next","下一首"){host.move(1)},dp(53),dp(48))
        transport.addView(TransportIcon(ctx,"queue","选集"){open("选集")},dp(44),dp(48))
        page.addView(transport)
        val menu=ctx.bar().apply { gravity=Gravity.CENTER }
        menu.addView(speed);menu.addView(badge,dp(98),dp(44));menu.addView(quality)
        page.addView(menu)
        page.addView(ctx.txt("播放队列",15f).apply { pad(0,12,0,6) })
        carousel=HorizontalScrollView(ctx).also { it.isHorizontalScrollBarEnabled=false;page.addView(it,-1,dp(97)) }
    }
    fun scheduleHide(){frame.removeCallbacks(hide);if(wide && drawer==null) frame.postDelayed(hide,4500)}
    fun refresh(animate:Boolean=false){
        val m=host.movies.getOrNull(host.index) ?:return
        title.text=m.title;author.text=m.uploader;position.text="${host.index+1} / ${host.movies.size}"
        val track=host.selection
        val premium=host.catalogue?.premium
        badge.bind(premium?.kind ?: AudioKind.STANDARD,track!=null && track.sound.kind!=AudioKind.STANDARD,animate)
        badge.isEnabled=premium!=null && host.catalogue?.audio?.any { it.kind==AudioKind.STANDARD }==true
        quality.text=track?.picture?.title?.substringBefore(' ') ?: "清晰度"
        speed.text=if(host.speed==1f) "倍速" else "${host.speed}×"
        val msg=host.message
        status.text=msg;status.visibility=if(msg.isEmpty()) View.GONE else View.VISIBLE
        data.text=host.shortStats()
        if(!wide && queueCardIndex!=host.index){queueCardIndex=host.index;renderCarousel()}
        tick()
    }
    private fun renderCarousel(){
        val rail=carousel ?:return;rail.removeAllViews();val cards=ctx.bar()
        rail.addView(cards)
        // A horizontal adapter supports long folders without eagerly decoding every cover.
        val remaining=host.movies.size-host.index-1
        if(remaining<=0){cards.addView(ctx.txt("已到收藏夹末尾",13f,muted));return}
        var next=host.index+1
        fun append(){
            val end=(next+8).coerceAtMost(host.movies.size)
            while(next<end){
                val i=next++; val movie=host.movies[i]
                val item=ctx.column().apply {pad(0,0,8,0);setOnClickListener { host.selectMovie(i) }}
                val art=ctx.cover(6);host.covers.bind(art,movie.cover);item.addView(art,dp(112),dp(63))
                item.addView(ctx.txt(movie.title,12f).line().apply { pad(0,5,0,0) },dp(110),-2);cards.addView(item)
            }
        }
        append();rail.scrollTo(0,0)
        rail.setOnScrollChangeListener { _:View,x:Int,_:Int,_:Int,_:Int ->
            if(next<host.movies.size && x+rail.width>cards.width-dp(250))append()
        }
    }
    fun tick(){
        val p=host.player
        if(!dragging){
            val dur=(p?.duration ?: 0L).coerceAtLeast(0)
            val now=p?.currentPosition ?: 0L
            elapsed.text=clockTime(now)+" / "+clockTime(dur)
            progress.progress=if(dur>0)(now*1000/dur).toInt().coerceIn(0,1000) else 0
        }
        play.playing=p?.playWhenReady==true && p.playerError==null && p.playbackState!=Player.STATE_ENDED
        if(panel=="音频详情" && lastDetails!=host.detailRows().toString()) open(panel,false)
    }
    fun close(animate:Boolean=true):Boolean {
        val old=drawer ?:return false; drawer=null;panel="";lastDetails=""
        val done={ frame.removeView(old);if(drawer==null){chrome.visibility=View.VISIBLE;scheduleHide()} }
        old.animate().cancel()
        if(animate)old.animate().alpha(0f).setDuration(160).withEndAction { done() }.start() else done()
        return true
    }
    fun open(name:String,animate:Boolean=true){
        drawer?.let { it.animate().cancel();frame.removeView(it) }
        frame.removeCallbacks(hide);chrome.visibility=View.GONE
        panel=name
        val layer=FrameLayout(ctx);drawer=layer;layer.setOnClickListener { close() };frame.addView(layer,size(-1,-1))
        val width=if(wide) (frame.width*.40f).toInt().coerceAtLeast(dp(240)).coerceAtMost(frame.width) else (frame.width*.92f).toInt()
        val back=object:View(ctx){
            val paint=Paint()
            override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){
                if(w>0)paint.shader=LinearGradient(0f,0f,w.toFloat(),0f,intArrayOf(0,0x19000000,0x85000000.toInt(),0xa3000000.toInt(),0xa3000000.toInt()),floatArrayOf(0f,.065f,.16f,.22f,1f),Shader.TileMode.CLAMP)
            }
            override fun onDraw(canvas:Canvas){canvas.drawRect(0f,0f,getWidth().toFloat(),getHeight().toFloat(),paint)}
        }
        layer.addView(back,FrameLayout.LayoutParams(width,-1,Gravity.RIGHT))
        val content=ctx.column().apply { isClickable=true;pad(3,2,14,8) }
        layer.addView(content,FrameLayout.LayoutParams((width*.81f).roundToInt(),-1,Gravity.RIGHT))
        val head=ctx.bar();head.addView(ctx.txt(if(name=="选集") "收藏夹" else name,19f),weight(1f))
        head.addView(arrow("›","收起"+name){close()},dp(42),dp(44));content.addView(head)
        if(name=="选集"){
            val list=ListView(ctx);list.dividerHeight=0
            list.adapter=MovieRows(ctx,host.covers,host.movies,{host.index},host::movieSpec)
            list.setSelection(host.index.coerceAtLeast(0));list.setOnItemClickListener { _,_,i,_ ->close();host.selectMovie(i) }
            content.addView(list,-1,-1)
        } else {
            val scroll=ScrollView(ctx);val rows=ctx.column();scroll.addView(rows);content.addView(scroll,-1,-1)
            fun choice(label:String,active:Boolean,vip:Boolean=false,click:()->Unit){
                val row=ctx.bar();row.background=ctx.round(0xe827282e.toInt(),7);row.pad(12,1,8,1)
                row.addView(ctx.txt(label,15f,if(active)pink else Color.WHITE))
                if(vip)row.addView(ctx.txt("  大会员",10f,pink))
                row.setOnClickListener { click() }
                rows.addView(row,LinearLayout.LayoutParams(-1,dp(42)).apply { bottomMargin=dp(6) })
            }
            when(name){
                "清晰度"->{
                    choice("自动",host.quality==0){host.setQuality(0);close()}
                    host.catalogue?.video?.forEach { tr->choice(tr.title,host.quality!=0 && host.selection?.picture?.id==tr.id,tr.vip){host.setQuality(tr.id);close()} }
                }
                "倍速"-> listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach { value ->
                    choice("${value}×",host.speed==value){ if(host.changeSpeed(value))close() }
                }
                "音频详情"->{
                    val values=host.detailRows();lastDetails=values.toString()
                    values.forEach { (k,v)->
                        if(v.isEmpty()){rows.addView(ctx.txt(k,12f,muted).apply { pad(0,10,0,5) })}
                        else {
                            val row=ctx.bar();row.addView(ctx.txt(k,13f,muted),weight(1f))
                            row.addView(ctx.txt(v,13f,if(v=="USB 独占")pink else Color.WHITE));rows.addView(row,-1,dp(29))
                        }
                    }
                    rows.addView(ctx.action("输出设置"){host.outputs()})
                }
            }
        }
        if(animate){
            layer.alpha=0f;content.translationX=dp(8).toFloat()
            layer.animate().alpha(1f).setDuration(220).start();content.animate().translationX(0f).setDuration(220).start()
        }
    }
    fun dispose(){frame.removeCallbacks(hide);video.player=null;drawer?.animate()?.cancel()}
}
