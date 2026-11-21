package com.pipratube.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
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
import android.widget.TextView
import android.widget.Toast

/** সেটিংস: এখানে বদলালেই অ্যাপের আচরণ বদলাবে */
object Config {
    const val HOME_URL = "https://pipratube.com/"

    /** true দিলে ডেস্কটপ লেআউট চাইবে (বড় টিভি স্ক্রিনে অনেক সময় ভালো লাগে) */
    const val DESKTOP_MODE = false

    const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /** মিডিয়া কী (ফরওয়ার্ড/রিওয়াইন্ড) চাপলে কত সেকেন্ড লাফাবে */
    const val SEEK_SECONDS = 10
}

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var splash: View

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var lastBackPress = 0L

    // রিমোটের ডি-প্যাডে ফোকাস দেখানো + ক্লিকযোগ্য এলিমেন্টকে ফোকাসযোগ্য করা
    private val tvScript = """
        (function(){
          if (window.__piTV) return; window.__piTV = true;
          var s = document.createElement('style');
          s.textContent = '*:focus{outline:3px solid #ff3d57 !important;outline-offset:2px !important;border-radius:6px}' +
                          'html{scroll-behavior:smooth}';
          (document.head || document.documentElement).appendChild(s);
          function mark(){
            var list = document.querySelectorAll('[onclick],[role=button],[role=link],[class*=card],[class*=btn],[class*=item]');
            for (var i = 0; i < list.length; i++) {
              var e = list[i];
              if (e.hasAttribute('tabindex')) continue;
              if (/^(A|BUTTON|INPUT|SELECT|TEXTAREA|VIDEO|IFRAME)${'$'}/.test(e.tagName)) continue;
              if (e.querySelector('a,button,input')) continue;
              e.setAttribute('tabindex', '0');
            }
          }
          mark(); setInterval(mark, 2000);
          document.addEventListener('keydown', function(ev){
            var a = document.activeElement;
            if ((ev.key === 'Enter' || ev.keyCode === 23) && a && a.getAttribute('tabindex') === '0' &&
                !/^(A|BUTTON|INPUT|TEXTAREA|SELECT)${'$'}/.test(a.tagName)) { a.click(); }
          }, true);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B0F15")) }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F15"))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(web, match())
        splash = buildSplash()
        root.addView(splash, match())
        setContentView(root)

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

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                // শুধু http/https অ্যাপের ভেতরে খুলবে; বাকি (intent:, tel: ইত্যাদি) বন্ধ
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(tvScript, null)
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
                root.addView(view, match())
                view.requestFocus()
            }

            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
                web.visibility = View.VISIBLE
                web.requestFocus()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress >= 85) splash.visibility = View.GONE
            }
        }
    }

    // ---------- রিমোট কী ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> { showMenu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                { video("v.paused ? v.play() : v.pause();"); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { video("v.play();"); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { video("v.pause();"); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                { video("v.currentTime += ${Config.SEEK_SECONDS};"); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND ->
                { video("v.currentTime -= ${Config.SEEK_SECONDS};"); return true }
        }
        return super.onKeyDown(keyCode, event)
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

    private fun showMenu() {
        val items = arrayOf("🏠  হোম", "🔄  রিলোড", "⬅  পিছনে যান", "✖  অ্যাপ থেকে বের হন")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("PipraTube")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> web.loadUrl(Config.HOME_URL)
                    1 -> web.reload()
                    2 -> if (web.canGoBack()) web.goBack()
                    3 -> finish()
                }
            }
            .show()
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
        root.removeAllViews()
        web.destroy()
        super.onDestroy()
    }
}
