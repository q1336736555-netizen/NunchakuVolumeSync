package cn.local.mv.test

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.video.PlaceholderSurface
import cn.local.mv.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On-device integration probe. Requires an existing login and an explicitly named test folder.
 * Mutes system output and renders to an offscreen surface. Physical DAC testing is separate. */
class PlaybackProbe : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle) { args = arguments; start() }
    override fun onStart() {
        val result = Bundle()
        var player: ExoPlayer? = null
        var surface: PlaceholderSurface? = null
        try {
            runOnMainSync { Bili.initialize(targetContext) }
            val account = Bili.account()
            val folder = Bili.folders(account.uid).first { it.title == args.getString("folder") }
            val items = Bili.movies(folder.id)
            val from = args.getString("start", "0").toInt()
            if (args.getString("cookieCompare") == "true") {
                val match=args.getString("match")
                val movie=if(match.isNullOrBlank()) items[from] else items.first { match in it.title }
                val s=Bili.streams(movie,account.key)
                result.putString("stream",movie.title+"\n"+CookieProbe.report(s))
                finish(Activity.RESULT_OK,result)
                return
            }
            val files = if (args.getString("file")=="true") items.drop(from).take(2).map { Bili.standardFile(it,account.key) } else null
            val audioProfile=args.getString("profile")=="audio"
            val streams = items.drop(from).take(2).map { Bili.catalogue(it,account.key,audioProfile).choose(80,true,8_000_000) }
            require(streams.size == 2)
            if (args.getString("transport") == "true") {
                val track=if(args.getString("track")=="audio")streams.first().sound else streams.first().picture
                val base = files?.first()?.first() ?: track.urls.first()
                val uri = android.net.Uri.parse(base)
                val ttl = uri.getQueryParameter("deadline")?.toLongOrNull()?.minus(System.currentTimeMillis()/1000)
                val req=okhttp3.Request.Builder().url(base).header("Referer",Bili.site)
                    .header("User-Agent",Bili.agent).header("Range","bytes=0-1023").build()
                PlaybackHttp.client.newCall(req).execute().use { response ->
                    val id=response.header("X-Request-ID")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) } ?: "—"
                    val bytes=if(response.isSuccessful) response.body?.byteStream()?.read(ByteArray(1024)) else 0
                    val manager=targetContext.getSystemService(android.hardware.usb.UsbManager::class.java)
                    val devices=manager.deviceList.values.count { d -> (0 until d.interfaceCount).any {
                        d.getInterface(it).interfaceClass==android.hardware.usb.UsbConstants.USB_CLASS_AUDIO
                    } }
                    result.putString("stream","HTTP ${response.code}; bytes=$bytes; TTL ${ttl}s; transport=${response.protocol}; "+
                        "rendition=${track.id}; codec=${track.codec}; request-id=$id; USB audio devices=$devices")
                }
                finish(Activity.RESULT_OK,result)
                return
            }
            val done = CountDownLatch(1)
            val rendered = mutableSetOf<Int>()
            var failure: String? = null
            runOnMainSync {
                surface = PlaceholderSurface.newInstance(targetContext,false)
                val p = ExoPlayer.Builder(targetContext).build()
                player = p
                p.volume = 0f
                p.setVideoSurface(surface)
                if(files!=null){
                    p.setMediaSources(files.map { urls ->
                        val http=mediaHttp()
                        ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(urls.first()))
                    })
                }else p.setMediaSources(streams.mapIndexed { i, s -> mvSource(items[from+i],s) })
                p.addListener(object: Player.Listener {
                    override fun onRenderedFirstFrame() {
                        val i = p.currentMediaItemIndex
                        if (p.duration <= 0 || !rendered.add(i)) return
                        if (i == 0) p.seekTo((p.duration-1200).coerceAtLeast(0))
                        else done.countDown()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                        failure = "首帧条目="+rendered.size+"; "+error.errorCodeName+" HTTP "+http?.responseCode
                        done.countDown()
                    }
                })
                p.prepare()
                p.play()
            }
            check(done.await(75,TimeUnit.SECONDS)) { "播放超时，已渲染 " + rendered }
            check(failure == null && rendered.size == 2) { failure ?: "未验证两段画面" }
            result.putString("stream","OK: " + streams.joinToString(" -> ") { it.label })
            finish(Activity.RESULT_OK,result)
        } catch(e: Throwable) {
            result.putString("stream","FAILED: " + e.javaClass.simpleName + " " + e.message?.substringBefore("https://"))
            finish(Activity.RESULT_CANCELED,result)
        } finally {
            runOnMainSync { player?.release(); surface?.release() }
        }
    }
}


