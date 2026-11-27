package cn.local.mv.test

import cn.local.mv.Bili
import cn.local.mv.PlaybackHttp
import cn.local.mv.Streams
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** The last network interceptor sees the headers that the production cookie policy selected. */
internal object CookieProbe {
    private fun <T> measure(request: Request, includeCookies: Boolean, read: (Response) -> T): Pair<Boolean,T> {
        var sent = false
        val client = PlaybackHttp.client.newBuilder()
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor { chain ->
                val scoped = chain.request()
                val actual = if(includeCookies) scoped else scoped.newBuilder().removeHeader("Cookie").build()
                sent = !actual.header("Cookie").isNullOrEmpty()
                chain.proceed(actual)
            }.build()
        val value = client.newCall(request).execute().use(read)
        return sent to value
    }

    fun report(streams: Streams): String = buildString {
        val builder = Request.Builder().header("Referer",Bili.site).header("User-Agent",Bili.agent)
        val account = builder.url("https://api.bilibili.com/x/web-interface/nav").build()
        for (include in listOf(false,true)) {
            val (sent,loggedIn) = measure(account,include) { response ->
                check(response.isSuccessful) { "账号对照 HTTP ${response.code}" }
                JSONObject(response.body!!.string()).optJSONObject("data")?.optBoolean("isLogin") == true
            }
            appendLine("account include=$include sent=$sent loggedIn=$loggedIn")
        }
        for(track in listOf(streams.picture,streams.sound)) {
            // Issue the URL once per track, then use exactly that URL in both arms.
            val jar=android.webkit.CookieManager.getInstance()
            val eligible=track.urls.filter { !jar.getCookie(it).isNullOrBlank() }
            appendLine("${track.codec} issued=${track.urls.size} withMatchingCookies=${eligible.size}")
            val req=builder.url(eligible.firstOrNull() ?: track.urls.first()).header("Range","bytes=0-1023").build()
            for(include in listOf(false,true)) {
                val (sent,observation)=measure(req,include) { response ->
                    val bytes=if(response.isSuccessful) response.body?.byteStream()?.use { it.read(ByteArray(1024)).coerceAtLeast(0) } ?: 0 else 0
                    "HTTP=${response.code} bytes=$bytes"
                }
                appendLine("${track.codec} include=$include sent=$sent $observation")
            }
        }
    }
}
