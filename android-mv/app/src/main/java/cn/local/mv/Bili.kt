package cn.local.mv

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

internal data class Folder(val id: Long, val title: String, val count: Int, val cover: String = "")
internal data class Movie(val bv: String, val title: String, val cover: String = "", val uploader: String = "", val seconds: Int = 0)
internal enum class AudioKind(val title: String) { STANDARD("标准音质"), HIRES("Hi-Res无损"), DOLBY("Dolby") }
internal data class Track(val id: Int, val urls: List<String>, val codec: String, val bandwidth: Int,
    val title: String, val vip: Boolean = false, val kind: AudioKind = AudioKind.STANDARD)
internal data class Streams(val picture: Track, val sound: Track) {
    val video get() = picture.urls
    val audio get() = sound.urls
    val label get() = sound.kind.title + " · " + picture.title
}
internal data class Catalogue(val video: List<Track>, val audio: List<Track>) {
    val premium get() = audio.firstOrNull { it.kind != AudioKind.STANDARD }
    fun choose(quality: Int, enhanced: Boolean, estimate: Long): Streams {
        val affordable = video.filter { it.bandwidth > 0 && it.bandwidth < estimate * 0.8 }
        val pic = video.firstOrNull { it.id == quality } ?: affordable.firstOrNull() ?: video.last()
        val sound = (if (enhanced) premium else null) ?: audio.firstOrNull { it.kind == AudioKind.STANDARD } ?: audio.first()
        return Streams(pic,sound)
    }
}
internal data class Account(val uid: Long, val key: String)
internal class ApiError(val code: Int, message: String) : Exception(message)

internal object Bili {
    const val site = "https://www.bilibili.com/"
    lateinit var agent: String
        private set
    fun initialize(context: android.content.Context) {
        agent = android.webkit.WebSettings.getDefaultUserAgent(context)
    }
    private val permutation = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13)

    private fun get(path: String): JSONObject {
        val request=okhttp3.Request.Builder().url("https://api.bilibili.com"+path)
            .header("Referer",site).header("User-Agent",agent).build()
        PlaybackHttp.client.newCall(request).execute().use { response ->
            if (response.code != 200) throw ApiError(response.code, "B站 HTTP " + response.code)
            val json = JSONObject(response.body?.string() ?: error("B站返回空响应"))
            val code = json.getInt("code")
            if (code != 0) throw ApiError(code, json.optString("message"))
            return json.getJSONObject("data")
        }
    }

    fun account(): Account {
        val data = get("/x/web-interface/nav")
        val images = data.getJSONObject("wbi_img")
        fun stem(name: String) = images.getString(name).substringAfterLast('/').substringBefore('.')
        val raw = stem("img_url") + stem("sub_url")
        return Account(data.getLong("mid"), permutation.joinToString("") { raw[it].toString() })
    }

    fun folders(uid: Long): List<Folder> {
        val list = get("/x/v3/fav/folder/created/list-all?up_mid=" + uid).optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }.map {
            Folder(it.getLong("id"), it.getString("title"), it.optInt("media_count"), it.optString("cover"))
        }
    }

    fun movies(id: Long): List<Movie> {
        val items = mutableListOf<Movie>()
        val visited = mutableSetOf<String>()
        var page = 1
        do {
            val data = get("/x/v3/fav/resource/list?media_id=" + id + "&pn=" + page + "&ps=20&order=mtime&type=0")
            val rows = data.optJSONArray("medias") ?: break
            if (rows.length() == 0) break
            val first = rows.getJSONObject(0).optString("id")
            if (!visited.add(first)) break
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val bv = row.optString("bvid")
                if (row.optInt("type") == 2 && bv.matches(Regex("BV[A-Za-z0-9]{10}")) && row.optString("title") != "已失效视频")
                    items.add(Movie(bv, row.optString("title"), row.optString("cover"), row.optJSONObject("upper")?.optString("name").orEmpty(),row.optInt("duration")))
            }
            page++
        } while (data.optBoolean("has_more"))
        return items
    }

    private fun sign(params: Map<String,String>, key: String): String {
        val all = params + ("wts" to (System.currentTimeMillis() / 1000).toString())
        val query = all.toSortedMap().entries.joinToString("&") {
            it.key + "=" + URLEncoder.encode(it.value,"UTF-8").replace("+","%20")
        }
        val hash = MessageDigest.getInstance("MD5").digest((query + key).toByteArray())
        return query + "&w_rid=" + hash.joinToString("") { "%02x".format(it) }
    }

    private fun rows(array: JSONArray?) = (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }
    private fun addresses(obj: JSONObject): List<String> {
        val primary = obj.optString("baseUrl").ifEmpty { obj.getString("base_url") }
        val backups = obj.optJSONArray("backupUrl") ?: obj.optJSONArray("backup_url") ?: JSONArray()
        return (listOf(primary) + (0 until backups.length()).map { backups.getString(it) })
            .filter { it.startsWith("https://") }.distinct()
    }

    fun catalogue(movie: Movie, key: String, audioProfile: Boolean = false): Catalogue {
        val cid = get("/x/web-interface/view?bvid=" + movie.bv).getLong("cid")
        val params = mutableMapOf("bvid" to movie.bv, "cid" to cid.toString(), "fnver" to "0",
            "platform" to "pc", "otype" to "json", "force_host" to "2")
        params["fnval"] = if(audioProfile) "272" else "4048"
        params["fourk"] = if(audioProfile) "0" else "1"
        if(!audioProfile) params["qn"]="80"
        val query = sign(params, key)
        val data = get("/x/player/wbi/playurl?" + query)
        val dash = data.optJSONObject("dash") ?: error("该视频没有可用的分轨播放流")
        val formats = rows(data.optJSONArray("support_formats")).associateBy { it.optInt("quality") }
        fun track(j: JSONObject, title: String, vip: Boolean = false, kind: AudioKind = AudioKind.STANDARD) =
            Track(j.getInt("id"),addresses(j),j.optString("codecs"),j.optInt("bandwidth"),title,vip,kind)
        val video = rows(dash.optJSONArray("video")).groupBy { it.getInt("id") }.map { (id, variants) ->
            val j = variants.firstOrNull { it.optString("codecs").startsWith("avc") } ?: variants.first()
            val f = formats[id]
            track(j,f?.optString("new_description")?.takeIf { it.isNotBlank() } ?: (j.optInt("height").toString()+"P"),
                f?.optBoolean("need_vip") == true)
        }.filter { it.urls.isNotEmpty() }.sortedByDescending { it.id }
        val audio = buildList {
            dash.optJSONObject("flac")?.optJSONObject("audio")?.let { add(track(it,"Hi-Res无损", kind=AudioKind.HIRES)) }
            rows(dash.optJSONObject("dolby")?.optJSONArray("audio")).maxByOrNull { it.optInt("bandwidth") }?.let {
                add(track(it,"Dolby",kind=AudioKind.DOLBY))
            }
            rows(dash.optJSONArray("audio")).maxByOrNull { it.optInt("bandwidth") }?.let { add(track(it,"标准音质")) }
        }.filter { it.urls.isNotEmpty() }
        check(video.isNotEmpty() && audio.isNotEmpty()) { "此 MV 没有可播放的音视频轨" }
        return Catalogue(video,audio)
    }
    fun streams(movie: Movie, key: String) = catalogue(movie,key).choose(80,true,8_000_000)

    /** Standard, single-file representation issued for this same signed-in account. */
    fun standardFile(movie: Movie, key: String): List<String> {
        val cid = get("/x/web-interface/view?bvid=" + movie.bv).getLong("cid")
        val query = sign(mapOf("bvid" to movie.bv, "cid" to cid.toString(), "fnval" to "1",
            "fnver" to "0", "qn" to "64", "force_host" to "2"), key)
        val data = get("/x/player/wbi/playurl?" + query)
        check(data.optInt("is_preview")==0) { "此账号只获得试看权限" }
        val media = rows(data.optJSONArray("durl")).singleOrNull() ?: error("服务端没有提供单文件版本")
        val backups = media.optJSONArray("backup_url")
        return (listOf(media.optString("url")) + List(backups?.length() ?: 0){backups!!.optString(it)})
            .filter { it.startsWith("https://") }.distinct().also { check(it.isNotEmpty()) { "没有 HTTPS 文件地址" } }
    }
}

