package cn.local.mv

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.Gravity
import android.widget.*
import android.text.TextUtils
import kotlinx.coroutines.*
import java.net.URL
import java.net.HttpURLConnection
import java.util.Locale
import kotlin.math.roundToInt

internal val ink = Color.rgb(17,17,20)
internal val card = Color.rgb(32,32,37)
internal val muted = Color.rgb(170,169,178)
internal val pink = Color.rgb(251,114,153)
internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
internal fun Context.round(color: Int, radius: Int) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat()
}
internal fun Context.txt(value: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color); includeFontPadding = false
    typeface = Typeface.create("sans-serif-light",Typeface.NORMAL); gravity = Gravity.CENTER_VERTICAL
}
internal fun TextView.line() = apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.bar() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
internal fun android.view.View.pad(left: Int, top: Int, right: Int, bottom: Int) {
    setPadding(context.dp(left),context.dp(top),context.dp(right),context.dp(bottom))
}
internal fun Context.action(text: String, description: String = text, click: () -> Unit) =
    txt(text,15f).apply {
        gravity = Gravity.CENTER; contentDescription = description; pad(9,0,9,0)
        minHeight = dp(44); isFocusable = true; setOnClickListener { click() }
    }
internal fun clockTime(ms: Long): String {
    val sec = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT,"%02d:%02d",sec/60,sec%60)
}
internal fun rate(hz: Int): String = if (hz <= 0) "—" else java.text.DecimalFormat("0.###").format(hz/1000.0)+" kHz"
internal class Covers(private val scope: CoroutineScope) {
    private val cache = object: LruCache<String,Bitmap>(10*1024*1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val io = Dispatchers.IO.limitedParallelism(4)
    fun bind(view: ImageView, address: String, large: Boolean = false) {
        val url = address.removePrefix("http:").let { if (it.startsWith("//")) "https:"+it else it }
        val key = url + (if (large) "@640w_360h_1c.webp" else "@240w_135h_1c.webp")
        if (view.tag == key) return
        view.tag = key; view.setImageDrawable(null); view.setBackgroundColor(card)
        if (!url.startsWith("https://")) return
        cache.get(key)?.let { view.setImageBitmap(it); return }
        scope.launch {
            val bmp = withContext(io) {
                cache.get(key) ?: runCatching {
                    val c = URL(key).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout=4500; c.readTimeout=4500
                        c.setRequestProperty("Referer",Bili.site)
                        c.inputStream.use { BitmapFactory.decodeStream(it) }
                    } finally { c.disconnect() }
                }.getOrNull()?.also { cache.put(key,it) }
            }
            if (view.tag == key && bmp != null) view.setImageBitmap(bmp)
        }
    }
}
internal fun Context.cover(radius: Int) = ImageView(this).apply {
    background = round(card,radius); outlineProvider = object: android.view.ViewOutlineProvider() {
        override fun getOutline(view: android.view.View, outline: Outline) {
            outline.setRoundRect(0,0,view.width,view.height,dp(radius).toFloat())
        }
    }
    clipToOutline=true; scaleType=ImageView.ScaleType.CENTER_CROP
}
internal class MovieRows(
    private val context: Context, private val covers: Covers, private val movies: List<Movie>,
    private val active: () -> Int, private val spec: (Movie) -> String
) : BaseAdapter() {
    private inner class Row : LinearLayout(context) {
        val art=context.cover(5); val title=context.txt("",15f).line()
        val duration=context.txt("",10f).apply { pad(3,1,3,1);background=context.round(0xb0000000.toInt(),3) }
        val meta=context.txt("",11f,muted).line(); val marker=context.txt("",18f)
        init {
            gravity=Gravity.CENTER_VERTICAL; pad(2,7,3,7)
            val preview=FrameLayout(context);preview.addView(art,-1,-1)
            preview.addView(duration,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.RIGHT))
            addView(preview,context.dp(100),context.dp(57))
            title.setSingleLine(false);title.maxLines=2
            val text=context.column().apply { pad(11,0,5,0); gravity=Gravity.CENTER_VERTICAL }
            text.addView(title); meta.pad(0,5,0,0); text.addView(meta); addView(text,LayoutParams(0,-2,1f))
            addView(marker)
        }
    }
    override fun getCount()=movies.size
    override fun getItem(position:Int)=movies[position]
    override fun getItemId(position:Int)=position.toLong()
    override fun getView(position:Int,convertView:android.view.View?,parent:android.view.ViewGroup):android.view.View {
        val row=convertView as? Row ?: Row(); val movie=movies[position]
        row.duration.text=clockTime(movie.seconds*1000L);row.duration.visibility=if(movie.seconds>0) android.view.View.VISIBLE else android.view.View.GONE
        row.title.text=movie.title; row.title.setTextColor(if(position==active()) pink else Color.WHITE)
        row.meta.text=listOf(movie.uploader.takeIf { it.isNotBlank() }?.let { "UP  "+it }.orEmpty(),spec(movie)).filter { it.isNotBlank() }.joinToString("   ·   ")
        row.marker.text=if(position==active()) "Ⅱ" else "▷"; row.marker.setTextColor(if(position==active()) pink else muted)
        covers.bind(row.art,movie.cover); return row
    }
}
