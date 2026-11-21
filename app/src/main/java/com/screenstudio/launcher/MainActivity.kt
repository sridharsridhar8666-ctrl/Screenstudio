package com.screenstudio.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

data class App(val label: String, val pkg: String, val icon: Drawable)

class MainActivity : Activity() {
    private lateinit var home: HomeView
    private lateinit var drawer: GridView
    private lateinit var prefs: SharedPreferences
    private var apps: List<App> = emptyList()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        prefs = getSharedPreferences("p", 0)
        val root = FrameLayout(this)
        home = HomeView(this)
        root.addView(home)
        drawer = GridView(this).apply {
            numColumns = 4; verticalSpacing = 36; visibility = View.GONE
            setBackgroundColor(0xF0000000.toInt()); setPadding(24, 160, 24, 120)
        }
        root.addView(drawer)
        setContentView(root)
        home.onSwipeUp = { showDrawer() }
        home.onLaunch = { launch(it) }
        home.onTheme = { prefs.edit().putInt("theme", it).apply() }
        home.theme = prefs.getInt("theme", 0)
        refresh()
    }

    private fun refresh() {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(i, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .filter { it.pkg != packageName }.sortedBy { it.label.lowercase() }
        drawer.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int) = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, g: ViewGroup?): View {
                val a = apps[p]
                val l = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                l.addView(ImageView(this@MainActivity).apply { setImageDrawable(a.icon); layoutParams = LinearLayout.LayoutParams(130, 130) })
                l.addView(TextView(this@MainActivity).apply { text = a.label; setTextColor(Color.WHITE); textSize = 11f; maxLines = 1; gravity = Gravity.CENTER })
                return l
            }
        }
        drawer.setOnItemClickListener { _, _, p, _ -> launch(apps[p].pkg); drawer.visibility = View.GONE }
        drawer.setOnItemLongClickListener { _, _, p, _ -> addToDock(apps[p].pkg); true }
        loadDock()
    }

    private fun defaultDock(): List<String> {
        val pm = packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL), Intent(Intent.ACTION_VIEW, Uri.parse("sms:")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE), Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
        )
        return intents.mapNotNull { pm.resolveActivity(it, 0)?.activityInfo?.packageName }
            .filter { it != "android" }.distinct()
    }

    private fun loadDock() {
        val saved = prefs.getString("dock", null)?.split(",")?.filter { it.isNotBlank() } ?: defaultDock()
        val pm = packageManager
        home.dock = saved.mapNotNull { p -> try { Pair(p, pm.getApplicationIcon(p)) } catch (e: Exception) { null } }
        home.invalidate()
    }

    private fun addToDock(pkg: String) {
        val cur = (prefs.getString("dock", null)?.split(",")?.filter { it.isNotBlank() } ?: defaultDock()).toMutableList()
        cur.remove(pkg); cur.add(pkg)
        while (cur.size > 4) cur.removeAt(0)
        prefs.edit().putString("dock", cur.joinToString(",")).apply()
        loadDock(); drawer.visibility = View.GONE
        Toast.makeText(this, "Added to dock", Toast.LENGTH_SHORT).show()
    }

    private fun showDrawer() { drawer.visibility = View.VISIBLE }
    private fun launch(pkg: String) { packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); drawer.visibility = View.GONE }
    override fun onResume() { super.onResume(); home.invalidate() }
    @Deprecated("") override fun onBackPressed() { if (drawer.visibility == View.VISIBLE) drawer.visibility = View.GONE }
}

class HomeView(c: Context) : View(c) {
    var theme = 0
    var dock: List<Pair<String, Drawable>> = emptyList()
    var onSwipeUp: () -> Unit = {}
    var onLaunch: (String) -> Unit = {}
    var onTheme: (Int) -> Unit = {}
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { invalidate(); h.postDelayed(this, 15000) } }
    private val rects = mutableListOf<RectF>()
    private val rightSide get() = theme == 1 || theme == 2

    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
            if (a != null && a.y - b.y > 120 && Math.abs(vy) > 400) { onSwipeUp(); return true }
            return false
        }
        override fun onLongPress(e: MotionEvent) { theme = (theme + 1) % 4; onTheme(theme); invalidate() }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            rects.forEachIndexed { i, r -> if (r.contains(e.x, e.y)) { onLaunch(dock[i].first); return true } }
            return false
        }
    })

    override fun onAttachedToWindow() { super.onAttachedToWindow(); h.post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); h.removeCallbacks(tick) }
    override fun onTouchEvent(e: MotionEvent) = gd.onTouchEvent(e)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val H = height.toFloat()
        val fg: Int
        when (theme) {
            0 -> { cv.drawColor(0xFF0B0709.toInt()); fg = Color.WHITE
                p.shader = LinearGradient(0f, 0f, w, H, 0xFFA01F37.toInt(), 0xFF560B17.toInt(), Shader.TileMode.CLAMP)
                val ys = floatArrayOf(0f, .08f, .16f, .26f, .36f, .46f, .58f, .70f, .82f, .92f, 1f)
                val xs = floatArrayOf(.58f, .52f, .61f, .50f, .58f, .47f, .57f, .49f, .58f, .52f, .56f)
                val path = Path().apply { moveTo(0f, 0f); for (i in xs.indices) lineTo(w * xs[i], H * ys[i]); lineTo(0f, H); close() }
                cv.drawPath(path, p); p.shader = null }
            1 -> { cv.drawColor(0xFF0A0A0A.toInt()); fg = 0xFFF4F4F4.toInt()
                p.color = 0xFFD9D9D9.toInt(); cv.drawCircle(w * .3f, H * .42f, w * .3f, p)
                p.color = 0xFF050505.toInt()
                cv.drawPath(Path().apply { moveTo(w * .26f, H * .12f); lineTo(w * .275f, H * .12f); lineTo(w * .30f, H * .62f)
                    lineTo(w * .42f, H); lineTo(w * .12f, H); lineTo(w * .24f, H * .62f); close() }, p) }
            2 -> { cv.drawColor(0xFF1B1E25.toInt()); fg = Color.WHITE
                p.color = 0xFFFF2D4A.toInt(); cv.drawCircle(w * .5f, H * .14f, w * .2f, p)
                p.color = 0xFF101114.toInt()
                cv.drawPath(Path().apply { moveTo(0f, H * .24f); lineTo(w * .22f, H * .16f); lineTo(w * .38f, H * .22f)
                    lineTo(w * .55f, H * .12f); lineTo(w * .78f, H * .24f); lineTo(w, H * .18f); lineTo(w, H); lineTo(0f, H); close() }, p) }
            else -> { fg = 0xFF141414.toInt()
                p.shader = LinearGradient(0f, 0f, 0f, H, 0xFFECEBE0.toInt(), 0xFFC4C8B9.toInt(), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, H, p); p.shader = null
                p.shader = RadialGradient(w * .55f, H * .55f, w * .6f, 0x66283428, 0x00283428, Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, w, H, p); p.shader = null }
        }
        val pad = w * .08f
        val right = rightSide
        val tx = if (right) w - pad else pad
        val align = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        val now = Date()
        p.color = fg; p.textAlign = align; p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        p.textSize = w * .17f
        cv.drawText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), tx, H * .08f + w * .17f, p)
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(now).uppercase()
        val sub = SimpleDateFormat("d MMMM", Locale.getDefault()).format(now).uppercase()
        cv.save(); cv.translate(tx, H * .66f); cv.rotate(-90f)
        p.textAlign = Paint.Align.LEFT; p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        p.textSize = w * .10f; p.letterSpacing = .08f
        val dayOff = if (right) -w * .03f else w * .09f
        cv.drawText(day, 0f, dayOff - (if (right) w * .0f else 0f), p)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); p.textSize = w * .032f; p.letterSpacing = .3f; p.alpha = 200
        cv.drawText(sub, 0f, dayOff + (if (right) -w * .05f else w * .06f), p)
        cv.restore(); p.alpha = 255; p.letterSpacing = 0f
        rects.clear()
        val s = w * .12f; val gap = s * .55f
        var y = H - pad * 1.8f - s
        for (i in dock.indices.reversed()) {
            val x = if (right) w - pad - s else pad
            val r = RectF(x, y, x + s, y + s)
            dock[i].second.apply { setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()); draw(cv) }
            rects.add(0, r); y -= s + gap
        }
        p.color = fg; p.alpha = 150; p.textAlign = Paint.Align.CENTER; p.textSize = w * .03f; p.typeface = Typeface.DEFAULT
        cv.drawText("swipe up for apps · hold to change theme", w / 2f, H - pad * .6f, p); p.alpha = 255
    }
}
