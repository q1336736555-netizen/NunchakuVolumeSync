package cn.local.mv

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.*
import androidx.media3.exoplayer.source.*
import java.io.IOException
import androidx.media3.common.C

/** Only try mirrors supplied by the playback response for this account and this exact representation. */
private class MirrorSource(val urls: List<String>, val delegate: HttpDataSource) : DataSource by delegate {
    override fun open(dataSpec: DataSpec): Long {
        var error: IOException? = null
        for (url in urls) {
            val uri = Uri.parse(url)
            var request = dataSpec.withUri(uri)
            if (uri.host == "upos-sz-mirrorcosov.bilivideo.com" && request.httpMethod == DataSpec.HTTP_METHOD_GET &&
                request.position == 0L && request.length == C.LENGTH_UNSET.toLong() &&
                request.httpRequestHeaders.keys.none { it.equals("Range",true) }) {
                request = request.withAdditionalHeaders(mapOf("Range" to "bytes=0-"))
            }
            try { return delegate.open(request) }
            catch (e: IOException) {
                error = e
                android.util.Log.w("MvStream", "CDN " + Uri.parse(url).host + " · " +
                    ((e as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: e.javaClass.simpleName))
                delegate.close()
            }
        }
        throw error ?: IOException("此音视频轨没有可用地址")
    }
}
internal fun mediaHttp() = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(PlaybackHttp.client).setUserAgent(Bili.agent)
    .setDefaultRequestProperties(mapOf("Referer" to Bili.site))
internal fun mvSource(movie: Movie, streams: Streams): MediaSource {
    val http = mediaHttp()
    fun source(urls: List<String>): MediaSource =
        ProgressiveMediaSource.Factory { MirrorSource(urls, http.createDataSource()) }
            .createMediaSource(MediaItem.Builder().setMediaId(movie.bv).setUri(urls.first()).build())
    return MergingMediaSource(true,source(streams.video),source(streams.audio))
}

