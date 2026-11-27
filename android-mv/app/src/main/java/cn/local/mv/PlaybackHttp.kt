package cn.local.mv

import android.webkit.CookieManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Use the login WebView's cookie domain/path rules on each individual HTTPS hop. */
internal object PlaybackHttp {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val builder = request.newBuilder().removeHeader("Cookie")
            val cookies = CookieManager.getInstance()
            if (url.isHttps) {
                cookies.getCookie(url.toString())?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie",it) }
            }
            val response = chain.proceed(builder.build())
            if (url.isHttps) response.headers("Set-Cookie").forEach { cookies.setCookie(url.toString(),it) }
            response
        }.build()
}
