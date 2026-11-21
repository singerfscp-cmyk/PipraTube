package com.pipratube.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** সেটিংস: এখানে বদলালেই অ্যাপের আচরণ বদলাবে */
object Config {
    const val HOME_URL = "https://pipratube.com/"

    /** true দিলে ডেস্কটপ লেআউট চাইবে */
    const val DESKTOP_MODE = false

    const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /** মিডিয়া কী (ফরওয়ার্ড/রিওয়াইন্ড) চাপলে কত সেকেন্ড লাফাবে */
    const val SEEK_SECONDS = 10

    /** নিচে ভেসে থাকা Home/Live TV/Upload/Shorts/For You বার লুকাবে */
    const val HIDE_BOTTOM_BAR = true

    /** সাইটের উপরের বাম কোণের ৩ দাগ (হ্যামবার্গার) চাপলে অ্যাপের সাইড মেনু খুলবে */
    const val HAMBURGER_OPENS_MENU = true

    /** প্রথমবার খুললে মাউস মোড চালু থাকবে কি না */
    const val MOUSE_MODE_DEFAULT = true
}

/** স্ক্রিনের ওপর ভেসে থাকা মাউস তীর */
class CursorView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val size = 30f * ctx.resources.displayMetrics.density
    var cx = 0f
    var cy = 0f

    init {
        isFocusable = false
        isClickable = false
        val s = size
        path.moveTo(0f, 0f)
        path.lineTo(0f, s)
        path.lineTo(0.28f * s, 0.78f * s)
        path.lineTo(0.48f * s, 1.2f * s)
        path.lineTo(0.62f * s, 1.12f * s)
        path.lineTo(0.42f * s, 0.72f * s)
        path.lineTo(0.78f * s, 0.72f * s)
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        canvas.restore()
    }

    fun moveTo(x: Float, y: Float) {
        cx = x
        cy = y
        invalidate()
    }
}

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var splash: View
    private lateinit var cursor: CursorView

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var lastBackPress = 0L
    private var mouseMode = true
    private var menuDialog: Dialog? = null
    private val handler = Handler(Looper.getMainLooper())

    /** সাইট থেকে অ্যাপের মেনু খোলার জন্য */
    inner class Bridge {
        @JavascriptInterface
        fun openMenu() {
            runOnUiThread { showMenu() }
        }
    }

    // সাইটের ভেতরে চলা স্ক্রিপ্ট: ফোকাস বর্ডার, নিচের বার লুকানো, নেভিগেশন, হ্যামবার্গার
    private val tvScript = """
        (function(){
          if (window.__piTV) return; window.__piTV = true;
          var LABELS = ['home','live tv','upload','shorts','for you'];
          var CLICKABLE = ['A','BUTTON','INPUT','SELECT','TEXTAREA','VIDEO','IFRAME'];
          function norm(t){ return (t||'').replace(/\s+/g,' ').trim().toLowerCase(); }

          var s = document.createElement('style');
          s.textContent = '*:focus{outline:3px solid #ff3d57 !important;outline-offset:2px !important;border-radius:6px}' +
                          'html{scroll-behavior:smooth}[data-pt-bar]{display:none !important}';
          (document.head || document.documentElement).appendChild(s);

          function mark(){
            var list = document.querySelectorAll('[onclick],[role=button],[role=link]');
            for (var i = 0; i < list.length; i++) {
              var e = list[i];
              if (e.hasAttribute('tabindex')) continue;
              if (CLICKABLE.indexOf(e.tagName) >= 0) continue;
              e.setAttribute('tabindex', '0');
            }
          }

          // নিচে ভেসে থাকা নেভিগেশন বার খুঁজে লুকানো
          function hideBar(){
            if (!window.__ptHideBar) return;
            var all = document.querySelectorAll('a,button,div,span,li,p,label');
            var vh = window.innerHeight, vw = window.innerWidth;
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              if (el.children.length > 3) continue;
              var tx = el.textContent;
              if (!tx || tx.length > 12) continue;
              if (LABELS.indexOf(norm(tx)) < 0) continue;
              if (el.closest('[data-pt-bar]')) continue;
              var p = el, hit = null;
              for (var k = 0; k < 8 && p && p !== document.body && p !== document.documentElement; k++) {
                var r = p.getBoundingClientRect();
                if (r.height > 0 && r.height < vh * 0.3 && r.top > vh * 0.6 && r.width > vw * 0.4) hit = p;
                p = p.parentElement;
              }
              if (hit) hit.setAttribute('data-pt-bar', '1');
            }
          }

          // লুকানো বারের বাটন প্রোগ্রাম দিয়ে চাপা (সাইড মেনু থেকে)
          window.__ptNav = function(label){
            var els = document.querySelectorAll('[data-pt-bar] *');
            var hit = null;
            for (var i = 0; i < els.length; i++) {
              if (norm(els[i].textContent) === label) hit = els[i];
            }
            if (hit) hit.click();
          };

          // উপরের বাম কোণের হ্যামবার্গারে চাপলে অ্যাপের মেনু
          if (window.AndroidTV) {
            document.addEventListener('click', function(ev){
              var el = ev.target;
              for (var i = 0; i < 4 && el && el !== document.body; i++) {
                var r = el.getBoundingClientRect();
                var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
                if (r.width > 0 && r.width < 110 && r.height < 110 &&
                    cx < window.innerWidth * 0.10 && cy < window.innerHeight * 0.2 &&
                    norm(el.textContent).length === 0) {
                  ev.preventDefault(); ev.stopPropagation();
                  window.AndroidTV.openMenu();
                  return;
                }
                el = el.parentElement;
              }
            }, true);
          }

          var timer = null;
          function later(){
            if (timer) return;
            timer = setTimeout(function(){ timer = null; hideBar(); mark(); }, 400);
          }
          new MutationObserver(later).observe(document.documentElement, {childList: true, subtree: true});
          hideBar(); mark();
          setTimeout(hideBar, 1500);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mouseMode = getSharedPreferences("pt", MODE_PRIVATE)
            .getBoolean("mouse", Config.MOUSE_MODE_DEFAULT)

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0F15")) }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F15"))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(web, match())
        splash = buildSplash()
        root.addView(splash, match())
        cursor = CursorView(this)
        cursor.visibility = if (mouseMode) View.VISIBLE else View.GONE
        root.addView(cursor, match())
        setContentView(root)
        root.post { cursor.moveTo(root.width / 2f, root.height / 2f) }

        setupWebView()

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(Config.HOME_URL)
        }
        web.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = if (Config.DESKTOP_MODE) {
            Config.DESKTOP_UA + " PipraTubeTV/1.0"
        } else {
            s.userAgentString + " PipraTubeTV/1.0 (AndroidTV)"
        }

        if (Config.HAMBURGER_OPENS_MENU) {
            web.addJavascriptInterface(Bridge(), "AndroidTV")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(
                    "window.__ptHideBar=${Config.HIDE_BOTTOM_BAR};$tvScript", null
                )
                splash.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.loadDataWithBaseURL(
                        Config.HOME_URL, errorHtml(), "text/html", "utf-8", null
                    )
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                web.visibility = View.INVISIBLE
                cursor.visibility = View.GONE
                root.addView(view, match())
                root.bringChildToFront(cursor)
                view.requestFocus()
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
                web.visibility = View.VISIBLE
                cursor.visibility = if (mouseMode) View.VISIBLE else View.GONE
                web.requestFocus()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress >= 85) splash.visibility = View.GONE
            }
        }
    }

    // ---------- রিমোট কী ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN

        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (down && event.repeatCount == 0) showMenu()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (down) video("v.paused ? v.play() : v.pause();")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) video("v.play();"); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) video("v.pause();"); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (down) video("v.currentTime += ${Config.SEEK_SECONDS};")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) video("v.currentTime -= ${Config.SEEK_SECONDS};")
                return true
            }
        }

        // মাউস মোড: ডি-প্যাড দিয়ে তীর নাড়ানো, OK দিয়ে ক্লিক
        if (mouseMode && customView == null && splash.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (down) moveCursor(event)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (down && event.repeatCount == 0) tap(cursor.cx, cursor.cy)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveCursor(e: KeyEvent) {
        val d = resources.displayMetrics.density
        val step = 9f * d * (1f + minOf(e.repeatCount, 25) / 4f)
        val edge = 12f * d
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        var x = cursor.cx
        var y = cursor.cy
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (y <= edge) wheel(0f, 2f) else y -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> if (y >= h - edge) wheel(0f, -2f) else y += step
            KeyEvent.KEYCODE_DPAD_LEFT -> if (x <= edge) wheel(-2f, 0f) else x -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (x >= w - edge) wheel(2f, 0f) else x += step
        }
        cursor.moveTo(x.coerceIn(0f, w - 1f), y.coerceIn(0f, h - 1f))
    }

    /** তীরের জায়গায় স্পর্শ (ক্লিক) পাঠানো */
    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        downEv.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(downEv)
        downEv.recycle()
        handler.postDelayed({
            val upEv = MotionEvent.obtain(
                t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0
            )
            upEv.source = InputDevice.SOURCE_TOUCHSCREEN
            web.dispatchTouchEvent(upEv)
            upEv.recycle()
        }, 60)
    }

    /** মাউসের হুইল স্ক্রল: dy > 0 হলে উপরে, dy < 0 হলে নিচে */
    private fun wheel(dx: Float, dy: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = cursor.cx
            y = cursor.cy
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx)
            setAxisValue(MotionEvent.AXIS_VSCROLL, dy)
        })
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            t, t, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        web.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun video(js: String) {
        web.evaluateJavascript(
            "(function(){var v=document.querySelector('video'); if(!v) return; $js})()", null
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> web.webChromeClient?.onHideCustomView()
            web.canGoBack() -> web.goBack()
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2000) {
                    super.onBackPressed()
                } else {
                    lastBackPress = now
                    Toast.makeText(this, "বের হতে আবার Back চাপুন", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------- সাইড মেনু ----------

    private fun setMouseMode(on: Boolean) {
        mouseMode = on
        getSharedPreferences("pt", MODE_PRIVATE).edit().putBoolean("mouse", on).apply()
        cursor.visibility = if (on && customView == null) View.VISIBLE else View.GONE
        Toast.makeText(
            this, if (on) "মাউস মোড চালু" else "মাউস মোড বন্ধ", Toast.LENGTH_SHORT
        ).show()
    }

    private fun nav(key: String) {
        web.evaluateJavascript("window.__ptNav && window.__ptNav('$key')", null)
    }

    private fun showMenu() {
        if (menuDialog?.isShowing == true) return
        val dp = resources.displayMetrics.density

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (28 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
        }
        val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        var first: View? = null

        fun addTitle(text: String) {
            col.addView(TextView(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, (16 * dp).toInt())
            })
        }

        fun addItem(label: String, action: () -> Unit) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 20f
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(if (hasFocus) Color.parseColor("#E50914") else Color.TRANSPARENT)
                }
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            col.addView(tv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * dp).toInt() })
            if (first == null) first = tv
        }

        addTitle("PipraTube")
        addItem("হোম") { nav("home") }
        addItem("লাইভ টিভি") { nav("live tv") }
        addItem("আপলোড") { nav("upload") }
        addItem("শর্টস") { nav("shorts") }
        addItem("ফর ইউ") { nav("for you") }
        addItem(if (mouseMode) "মাউস মোড: চালু (বন্ধ করুন)" else "মাউস মোড: বন্ধ (চালু করুন)") {
            setMouseMode(!mouseMode)
        }
        addItem("রিলোড") { web.reload() }
        addItem("পিছনে যান") { if (web.canGoBack()) web.goBack() }
        addItem("অ্যাপ থেকে বের হন") { finish() }

        dialog.setContentView(ScrollView(this).apply { addView(col) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#F20B0F15")))
            setLayout((340 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.START)
        }
        dialog.setOnDismissListener { web.requestFocus() }
        menuDialog = dialog
        dialog.show()
        first?.requestFocus()
    }

    // ---------- UI ----------

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun buildSplash(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0F15"))
            isClickable = true
        }
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_launcher) }
        box.addView(logo, LinearLayout.LayoutParams(220, 220))
        val title = TextView(this).apply {
            text = "PipraTube"
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        box.addView(title)
        box.addView(ProgressBar(this))
        return box
    }

    private fun errorHtml(): String = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="background:#0b0f15;color:#fff;font-family:sans-serif;text-align:center;padding-top:15vh">
        <h1 style="font-size:42px">ইন্টারনেট সংযোগ পাওয়া যায়নি</h1>
        <p style="font-size:26px;color:#aab">আপনার ওয়াই-ফাই / ইন্টারনেট চেক করে আবার চেষ্টা করুন।</p>
        <a id="r" href="${Config.HOME_URL}" style="display:inline-block;margin-top:30px;padding:18px 48px;
           background:#e50914;color:#fff;font-size:28px;border-radius:12px;text-decoration:none">আবার চেষ্টা করুন</a>
        <script>document.getElementById('r').focus();</script>
        </body></html>
    """.trimIndent()

    // ---------- লাইফসাইকেল ----------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        menuDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        root.removeAllViews()
        web.destroy()
        super.onDestroy()
    }
}
