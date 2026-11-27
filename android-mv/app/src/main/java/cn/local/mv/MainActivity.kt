package cn.local.mv

import android.app.*
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlinx.coroutines.*
import org.ostrichbat2k.usbdac.UsbAudioManager.PlaybackRoute
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val usb get()=(application as MvApp).usb
    private val prefs by lazy { getSharedPreferences("library",MODE_PRIVATE) }
    internal val covers=Covers(scope)
    internal var movies=emptyList<Movie>();private set
    internal var index=0;private set
    internal var player:ExoPlayer?=null;private set
    internal var catalogue:Catalogue?=null;private set
    internal var selection:Streams?=null;private set
    internal var quality=80;private set
    internal var speed=1f;private set
    internal var message="";private set
    private var enhanced=true
    private var exclusive=true
    private var loginPending=false
    private var showLoginResult=false
    private var lastPosition=0L
    private var resumePlay=true
    private var restore=false
    private var waitingUsb=false
    private var attempts=0
    private var rollback:Streams?=null
    private var previousQuality=80
    private var previousEnhanced=true
    private var pendingSetting=false
    private var job:Job?=null
    private var routeJob:Job?=null
    private var screen=""
    private var account:Account?=null
    private var folder:Folder?=null
    private lateinit var root:FrameLayout
    private var ui:PlayerScreen?=null
    private var sourceRate=0
    private var sourceBits=0
    private var sourceKbps=0
    private var ready=false
    private var refreshShelf:(()->Unit)?=null
    private val meter by lazy { DefaultBandwidthMeter.getSingletonInstance(this) }
    override fun onCreate(state:Bundle?){
        super.onCreate(state);window.statusBarColor=ink;window.navigationBarColor=ink
        root=FrameLayout(this);root.setBackgroundColor(ink);setContentView(root)
        exclusive=prefs.getBoolean("exclusive",true);enhanced=prefs.getBoolean("enhanced",true)
        quality=prefs.getInt("quality",80);speed=if(exclusive)1f else prefs.getFloat("speed",1f)
        library(true)
    }
    private fun stopPlayer(){ui?.video?.player=null;val exo=player;player=null;exo?.release();ready=false;window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    private fun clear(name:String){
        job?.cancel();routeJob?.cancel();waitingUsb=false;restore=false
        val leaving=screen=="player" || screen=="shelf";ui?.dispose();ui=null;refreshShelf=null;stopPlayer()
        if(leaving){usb.releaseClaim(0);fullscreen(false)}
        screen=name;root.removeAllViews();root.setBackgroundColor(ink)
    }
    private fun work(block:suspend ()->Unit,error:(Exception)->Unit){
        job?.cancel();job=scope.launch {
            try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error(e)}
        }
    }
    private fun page()=column().apply {pad(16,4,16,8);root.addView(this,-1,-1)}
    private fun nav(page:LinearLayout,title:String,back:(()->Unit)?=null):LinearLayout{
        val bar=bar()
        if(back!=null)bar.addView(action("‹","返回",back).apply { textSize=25f },dp(35),dp(44))
        bar.addView(txt(title,20f).line(),LinearLayout.LayoutParams(0,-2,1f));page.addView(bar,-1,dp(49));return bar
    }
    private fun hint(p:LinearLayout,v:String)=txt(v,13f,muted).apply {pad(0,7,0,7);p.addView(this)}
    private fun login(){loginPending=true;startActivity(Intent(this,LoginActivity::class.java))}
    override fun onResume(){super.onResume();if(loginPending){loginPending=false;showLoginResult=true;library(false)}}
    private fun library(reopen:Boolean){
        clear("library");val page=page();val bar=nav(page,"我的收藏夹")
        val login=action("登录 B 站"){login()};bar.addView(login);bar.addView(action("刷新"){library(false)})
        val status=hint(page,"正在读取收藏夹…")
        work({
            val a=withContext(Dispatchers.IO){Bili.account()};account=a;login.visibility=View.GONE
            if(showLoginResult){toast("B 站登录成功");showLoginResult=false}
            val folders=withContext(Dispatchers.IO){Bili.folders(a.uid)}
            status.text=if(folders.isEmpty()) "还没有创建收藏夹" else "已登录 · ${folders.size} 个收藏夹"
            val recent=folders.find { it.id==prefs.getLong("folder",-1L) }
            if(reopen && recent!=null){openFolder(recent);return@work}
            val scroll=ScrollView(this);val items=column();scroll.addView(items);page.addView(scroll)
            folders.forEach { f->
                val row=bar();val art=cover(8);covers.bind(art,f.cover);row.addView(art,dp(83),dp(50))
                val copy=column().apply {pad(13,0,0,0);addView(txt(f.title,17f).line());addView(txt("${f.count} 个视频",12f,muted))}
                row.addView(copy,LinearLayout.LayoutParams(0,-2,1f));row.addView(txt("›",20f,muted))
                row.setOnClickListener {openFolder(f)};items.addView(row,-1,dp(66))
            }
        },{status.text=if(it is ApiError && it.code==-101) "登录后即可读取自己的收藏夹" else "读取失败 · "+it.localizedMessage})
    }
    private fun openFolder(f:Folder,keepPlayer:Boolean=false){
        if(keepPlayer){
            screen="shelf";ui?.dispose();ui=null;root.removeAllViews();fullscreen(false)
        }else{clear("folder");folder=f;movies=emptyList();prefs.edit().putLong("folder",f.id).apply()}
        val page=page();nav(page,"我的收藏夹"){library(false)}.addView(txt("已登录",12f,muted))
        val hero=FrameLayout(this);hero.background=round(card,12);hero.clipToOutline=true
        val art=cover(12);covers.bind(art,f.cover,true);hero.addView(art,-1,-1)
        hero.addView(View(this).apply { background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(0xda09090b.toInt(),Color.TRANSPARENT)) },-1,-1)
        val cap=column().apply {pad(16,0,12,13);addView(txt(f.title,24f).line());addView(txt("${f.count} 个视频",12f)) }
        hero.addView(cap,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));page.addView(hero,-1,((resources.displayMetrics.widthPixels-dp(32))*9/16).coerceAtMost(dp(215)))
        val go=action("正在读取…"){}.apply {background=round(pink,7);isEnabled=false}
        page.addView(go,LinearLayout.LayoutParams(-1,dp(40)).apply {topMargin=dp(10);bottomMargin=dp(7)})
        val status=hint(page,"")
        fun showContents(loaded:List<Movie>){
            if(loaded.isEmpty()){status.text="这个收藏夹里没有可播放的 MV";go.visibility=View.GONE;return}
            if(f.cover.isBlank())covers.bind(art,loaded.first().cover,true)
            val last=loaded.indexOfFirst { it.bv==prefs.getString("last_"+f.id,"") }
            fun showPlayer(i:Int){
                if(!keepPlayer){enter(i);return}
                refreshShelf=null;screen="player";mount()
                if(i!=index) selectMovie(i) else if(player==null)retry()
            }
            go.text=if(last>=0) "▷  继续连播" else "▷  顺序播放";go.contentDescription=go.text;go.isEnabled=true;go.setOnClickListener {showPlayer(if(keepPlayer)index else last.coerceAtLeast(0))}
            status.visibility=View.GONE
            val list=ListView(this);list.dividerHeight=0
            val rows=MovieRows(this,covers,loaded,{if(keepPlayer)index else last},::movieSpec);list.adapter=rows
            list.setOnItemClickListener { _,_,i,_->showPlayer(i) };page.addView(list,LinearLayout.LayoutParams(-1,0,1f))
            if(keepPlayer || last>=0){
                val mini=bar();mini.background=round(card,8);mini.pad(6,3,6,3)
                val artMini=cover(5);mini.addView(artMini,dp(57),dp(35))
                val name=txt("",13f).line().apply{pad(10,0,4,0)};mini.addView(name,LinearLayout.LayoutParams(0,-2,1f))
                val button=action("▷","播放或暂停"){if(keepPlayer)playPause() else showPlayer(last)}
                mini.addView(button,dp(44),dp(44));mini.setOnClickListener{showPlayer(if(keepPlayer)index else last)}
                page.addView(mini,-1,dp(50))
                var shown=-1
                val update={
                    val i=if(keepPlayer)index else last
                    if(i!=shown){shown=i;name.text=loaded[i].title;covers.bind(artMini,loaded[i].cover);rows.notifyDataSetChanged()}
                    button.text=if(keepPlayer && player?.playWhenReady==true) "Ⅱ" else "▷"
                }
                update();if(keepPlayer)refreshShelf=update
            }
        }
        if(keepPlayer)showContents(movies) else work({
            movies=withContext(Dispatchers.IO){Bili.movies(f.id)};showContents(movies)
        },{status.text="读取失败 · "+it.localizedMessage;go.text="重试";go.isEnabled=true;go.setOnClickListener{openFolder(f)}})
    }
    internal fun movieSpec(m:Movie)=prefs.getString("spec_"+m.bv,"").orEmpty()
    internal val folderName get()=folder?.title.orEmpty()
    private fun enter(i:Int){
        clear("player");index=i;attempts=0;mount()
        routeJob=scope.launch {
            launch {usb.playbackRoute.collect { ui?.refresh() }}
            launch {usb.devices.collect { list->if(waitingUsb && list.any { it.hasPermission }){
                waitingUsb=false;load(index,lastPosition,resumePlay)
            } }}
            while(isActive){captureFormat();ui?.tick();refreshShelf?.invoke();delay(350)}
        }
        load(i)
    }
    private fun mount(){
        ui?.dispose();root.removeAllViews()
        val wide=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
        immersive(wide);ui=PlayerScreen(this,wide).also {root.addView(it.frame);it.video.player=player;it.refresh();it.scheduleHide()}
    }
    @Suppress("DEPRECATION")
    private fun immersive(full:Boolean){
        window.decorView.systemUiVisibility=if(full) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else View.SYSTEM_UI_FLAG_VISIBLE
    }
    internal fun fullscreen(on:Boolean){
        requestedOrientation=if(on)ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        immersive(on)
    }
    override fun onConfigurationChanged(config:Configuration){super.onConfigurationChanged(config);if(screen=="player") mount()}
    internal fun leavePlayer(){folder?.let {openFolder(it,player!=null && player?.playerError==null)}}
    internal fun move(delta:Int){val i=index+delta;if(i in movies.indices)selectMovie(i)}
    internal fun selectMovie(i:Int){attempts=0;load(i)}
    internal fun playPause(){
        val p=player ?: run {retry();return}
        if(p.playerError!=null){retry();return}
        if(p.playbackState==Player.STATE_ENDED){p.seekTo(0);p.play()} else if(p.playWhenReady)p.pause() else p.play()
        ui?.tick();ui?.scheduleHide()
    }
    internal fun retry(){attempts=0;load(index,player?.currentPosition ?: lastPosition,true)}
    internal fun outputs(){
        val devices=usb.devices.value
        val options=devices.map { "USB 独占 · "+it.displayName }+"使用系统输出"
        AlertDialog.Builder(this).setTitle(if(devices.isEmpty())"尚未检测到 USB DAC" else "音频输出")
            .setItems(options.toTypedArray()){_,n->
                lastPosition=player?.currentPosition ?: lastPosition;resumePlay=player?.playWhenReady ?: resumePlay
                exclusive=n<devices.size;prefs.edit().putBoolean("exclusive",exclusive).apply()
                speed=if(exclusive)1f else prefs.getFloat("speed",1f)
                if(exclusive && !devices[n].hasPermission){
                    waitingUsb=true;stopPlayer();setMessage("请允许 USB 访问");usb.requestPermission(devices[n].device)
                }else {if(!exclusive)usb.releaseClaim(0);load(index,lastPosition,resumePlay)}
            }.show()
    }
    private fun setMessage(v:String){message=v;ui?.refresh()}
    private fun load(i:Int,from:Long=0L,play:Boolean=true){
        job?.cancel();stopPlayer();index=i;lastPosition=from;resumePlay=play;rollback=null;pendingSetting=false
        catalogue=null;selection=null;sourceRate=0;sourceBits=0;sourceKbps=0
        setMessage("正在加载…")
        if(exclusive && usb.devices.value.none { it.hasPermission }){
            setMessage("USB 独占 · 请连接 DAC 或选择系统输出");outputs();return
        }
        val a=account ?:return;val movie=movies[i]
        work({
            val cat=withContext(Dispatchers.IO){Bili.catalogue(movie,a.key)};catalogue=cat
            if(exclusive){usb.triggerActivation();usb.awaitActivation()}
            val renderers=if(exclusive)UsbRenderers(this,usb) else DefaultRenderersFactory(this)
            renderers.setEnableAudioFloatOutput(true)
            val exo=ExoPlayer.Builder(this,renderers).setBandwidthMeter(meter).build()
            player=exo;ui?.video?.player=exo;listen(exo,i,movie)
            val s=cat.choose(quality,enhanced,meter.bitrateEstimate)
            startTracks(s,from,play,false)
        },{e->
            setMessage((e.localizedMessage ?: "读取失败")+" · 点此重试")
            if(e !is ApiError || e.code !in listOf(-101,-352,-412,403,412,429))skip(i)
        })
    }
    private fun startTracks(s:Streams,from:Long,play:Boolean,animate:Boolean){
        val exo=player ?:return
        selection=s;ready=false;sourceRate=0;sourceBits=0;sourceKbps=0;message=""
        exo.setMediaSource(mvSource(movies[index],s),from.coerceAtLeast(0))
        exo.setPlaybackSpeed(speed);exo.playWhenReady=play;exo.prepare()
        ui?.refresh(animate);ui?.scheduleHide()
    }
    private fun listen(exo:ExoPlayer,i:Int,movie:Movie){
        exo.addListener(object:Player.Listener{
            override fun onPlaybackStateChanged(state:Int){
                if(exo!==player)return
                if(state==Player.STATE_READY){
                    ready=true;attempts=0;rollback=null
                    val edit=prefs.edit().putString("last_"+folder!!.id,movie.bv)
                    if(pendingSetting){edit.putInt("quality",quality).putBoolean("enhanced",enhanced);pendingSetting=false}
                    edit.apply();captureFormat();ui?.refresh()
                }
                if(state==Player.STATE_ENDED){
                    if(i<movies.lastIndex)root.post {if(player===exo)load(i+1)}
                    else setMessage("收藏夹已播完 · 点此重播")
                }
            }
            override fun onIsPlayingChanged(isPlaying:Boolean){
                if(exo!==player)return
                if(isPlaying)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                ui?.tick();ui?.scheduleHide()
            }
            override fun onPlayerError(error:PlaybackException){
                if(exo!==player)return
                val old=rollback
                if(old!=null){
                    rollback=null;quality=previousQuality;enhanced=previousEnhanced;pendingSetting=false
                    toast("切换失败，已恢复上一档");startTracks(old,lastPosition,exo.playWhenReady,true);return
                }
                val http=generateSequence<Throwable>(error){it.cause}.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val rejected=http in listOf(401,403,412,429)
                setMessage(if(rejected)"视频服务器返回 HTTP "+http+" · 点此重试" else "播放失败 · "+error.errorCodeName+" · 点此重试")
                if(!rejected && error.errorCode in 2000..3999)skip(i)
            }
        })
    }
    private fun skip(i:Int){
        if(++attempts>=3 || i>=movies.lastIndex)return
        val expected=job
        root.postDelayed({if(screen in listOf("player","shelf") && i==index && expected===job && !isFinishing && !isDestroyed && !restore)load(i+1)},1500)
    }
    private fun changeTracks(q:Int,high:Boolean,animate:Boolean){
        val cat=catalogue ?:return;val p=player ?:return
        if(pendingSetting){toast("音轨正在切换");return}
        val next=cat.choose(q,high,meter.bitrateEstimate)
        previousQuality=quality;previousEnhanced=enhanced;quality=q;enhanced=high
        if(next==selection){
            prefs.edit().putInt("quality",q).putBoolean("enhanced",high).apply();ui?.refresh();return
        }
        rollback=selection;lastPosition=p.currentPosition.coerceAtLeast(0);pendingSetting=true
        startTracks(next,lastPosition,p.playWhenReady,animate)
    }
    internal fun toggleAudio(){
        val cat=catalogue ?:return
        if(cat.premium==null || cat.audio.none{it.kind==AudioKind.STANDARD})return
        changeTracks(quality,selection?.sound?.kind==AudioKind.STANDARD,true)
    }
    internal fun setQuality(id:Int)=changeTracks(id,enhanced,false)
    internal fun changeSpeed(v:Float):Boolean {
        if(exclusive && v!=1f){toast("USB 独占原速输出，切换到系统输出可使用倍速");return false}
        speed=v;player?.setPlaybackSpeed(v)
        if(!exclusive)prefs.edit().putFloat("speed",v).apply()
        ui?.refresh();return true
    }
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
    private fun captureFormat(){
        if(!ready)return
        val f=player?.audioFormat ?: return
        val hz=f.sampleRate.coerceAtLeast(0)
        var bits=when(f.pcmEncoding){
            C.ENCODING_PCM_16BIT->16;C.ENCODING_PCM_24BIT->24;C.ENCODING_PCM_32BIT,C.ENCODING_PCM_FLOAT->32;else->0
        }
        if(bits==0 && f.sampleMimeType=="audio/flac"){
            val head=f.initializationData.firstOrNull()
            if(head!=null && head.size>=42 && head[0]==102.toByte() && head[1]==76.toByte() && head[2]==97.toByte() && head[3]==67.toByte())
                bits=1+(((head[20].toInt() and 1) shl 4) or ((head[21].toInt() and 240) ushr 4))
        }
        val kbps=(selection?.sound?.bandwidth ?:0)/1000
        if(hz==sourceRate && bits==sourceBits && kbps==sourceKbps)return
        sourceRate=hz;sourceBits=bits;sourceKbps=kbps
        val spec=listOfNotNull(bits.takeIf{it>0}?.let{"${it} bit"},hz.takeIf{it>0}?.let(::rate)).joinToString(" · ")
        if(spec.isNotBlank())prefs.edit().putString("spec_"+movies[index].bv,spec).apply()
        ui?.refresh()
    }
    internal fun shortStats()=listOfNotNull(sourceRate.takeIf{it>0}?.let(::rate),sourceBits.takeIf{it>0}?.let{"${it} bit"},
        sourceKbps.takeIf{it>0}?.let{"${it} kbps"}).joinToString("   ·   ").ifBlank { "音频详情" }
    internal fun detailRows():List<Pair<String,String>>{
        val direct=if(exclusive && ready)usb.playbackRoute.value as? PlaybackRoute.Direct else null
        return listOf("当前音源" to "", "音轨" to (selection?.sound?.kind?.title ?: "—"),
            "编码" to (player?.audioFormat?.sampleMimeType?.substringAfter('/')?.uppercase() ?: selection?.sound?.codec ?: "—"),
            "采样率" to rate(sourceRate),"位深" to (sourceBits.takeIf{it>0}?.let{"${it} bit"} ?: "—"),
            "音频输出" to "", "输出模式" to (if(!exclusive)"系统输出" else if(direct!=null)"USB 独占" else "等待 USB"),
            "采样率" to rate(direct?.sampleRateHz ?:0),"位深" to (direct?.bitDepth?.let{"${it} bit"} ?: "—"))
    }
    override fun onStop(){
        super.onStop()
        if(screen=="player" || screen=="shelf"){
            restore=player!=null || job?.isActive==true
            if(restore){
                lastPosition=player?.currentPosition ?:lastPosition;resumePlay=player?.playWhenReady ?:resumePlay
                job?.cancel();stopPlayer();usb.releaseClaim(0)
            }
        }
    }
    override fun onStart(){super.onStart();if(screen in listOf("player","shelf") && restore){restore=false;load(index,lastPosition,resumePlay)}}
    @Deprecated("Platform back navigation")
    override fun onBackPressed(){when(screen){
        "player"->if(ui?.close()!=true){if(ui?.wide==true)fullscreen(false) else leavePlayer()}
        "folder","shelf"->library(false);else->super.onBackPressed()
    }}
    override fun onDestroy(){scope.cancel();ui?.dispose();stopPlayer();usb.releaseClaim(0);super.onDestroy()}
}
