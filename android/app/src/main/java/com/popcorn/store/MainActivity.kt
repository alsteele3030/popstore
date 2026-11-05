package com.popcorn.store

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/** One pending download: where the APK is, whether to delete it afterwards, and its display name. */
data class InstallJob(val file: File, val deleteAfter: Boolean, val name: String)

/**
 * Lets InstallReceiver tell the open screen what is happening so the pop-up can follow along.
 * The listener returns true when the screen has taken over the clean-up (deleting the installer).
 */
object InstallEvents {
    var onStatus: ((name: String, status: Int, path: String, deleteAfter: Boolean) -> Boolean)? = null
}

class MainActivity : AppCompatActivity() {

    companion object {
        val jobs = mutableMapOf<Long, InstallJob>()

        // states of the three steps shown in the pop-up
        const val PENDING = 0
        const val ACTIVE = 1
        const val DONE = 2
        const val FAILED = 3
        const val SKIPPED = 4
    }

    private lateinit var web: WebView
    private lateinit var rootView: FrameLayout

    // pop-up views
    private lateinit var overlay: FrameLayout
    private lateinit var popupCard: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var stepViews: Array<TextView>
    private lateinit var statusView: TextView
    private lateinit var barView: ProgressBar
    private lateinit var hintView: TextView
    private lateinit var doneButton: Button
    private lateinit var openButton: Button
    private var lastPkg: String? = null

    private val stepNames = arrayOf("Download", "Install", "Delete installer")
    private val stepStates = IntArray(3)
    private var pink = Color.parseColor("#FF4D8D")
    private var accent = Color.parseColor("#7C4DFF")
    private var cardColor = Color.parseColor("#1B1535")
    private var textColor = Color.WHITE
    private val green = Color.parseColor("#4ADE80")
    private val red = Color.parseColor("#F87171")
    private val gray = Color.parseColor("#8F89B3")

    private val ui = Handler(Looper.getMainLooper())
    private val sessionJobs = mutableMapOf<Int, InstallJob>()
    private var waitingForPermission: InstallJob? = null
    private var waitingUrl: String? = null
    private var polling = false

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val job = jobs.remove(id) ?: return
            onDownloaded(id, job)
        }
    }

    /** Gives the install a percentage while Android is working on it. */
    private val sessionCb = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) {}
        override fun onBadgingChanged(sessionId: Int) {}
        override fun onActiveChanged(sessionId: Int, active: Boolean) {}
        override fun onFinished(sessionId: Int, success: Boolean) {}
        override fun onProgressChanged(sessionId: Int, progress: Float) {
            if (!sessionJobs.containsKey(sessionId)) return
            val pct = (progress * 100).toInt()
            if (pct in 1..99) setStatus("Installing… $pct%", pct, "Almost there")
        }
    }

    /** Checks the download every 0.4 seconds and moves the bar. Stops by itself when nothing is downloading. */
    private val poll = object : Runnable {
        override fun run() {
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            var shown = false
            for ((id, _) in jobs.toMap()) {
                dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    if (c.moveToFirst() && !shown) {
                        shown = true
                        val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (total > 0) {
                            val pct = (got * 100 / total).toInt()
                            setStatus("Downloading… $pct%", pct, "")
                        } else {
                            setStatus("Downloading…", null, "")
                        }
                    }
                }
            }
            if (jobs.isNotEmpty()) ui.postDelayed(this, 400) else polling = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        buildPopup()

        // The pop-up sits on top of the whole screen, so it can't be missed.
        val root = FrameLayout(this)
        rootView = root
        root.setBackgroundColor(Color.parseColor("#0E0A1F"))
        // Keep the page (and its Popcorn PlayStore logo at the top) out from under the status bar / camera cut-out
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        root.addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)
        web.requestFocus()

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Stay inside the app for our own store; send everything else to the browser.
                val ourHost = Uri.parse(BuildConfig.STORE_URL).host
                if (request.url.host == ourHost) return false
                openExternal(request.url.toString())
                return true
            }
        }
        // The store page calls Android.install(...) and Android.openLink(...)
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl(BuildConfig.STORE_URL)

        ContextCompat.registerReceiver(
            this, downloadDone,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        packageManager.packageInstaller.registerSessionCallback(sessionCb, ui)

        // Results from InstallReceiver: the confirm screen, success, failure
        InstallEvents.onStatus = { name, status, path, del ->
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    setStatus("Waiting for you…", null, "Tap Install on the next screen")
                    false
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    sessionJobs.values.removeAll { it.name == name }
                    finishInstall(name, path, del, true)
                    true
                }
                else -> {
                    sessionJobs.values.removeAll { it.name == name }
                    finishInstall(name, path, del, false)
                    true
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back hides the pop-up (the install carries on). Then it closes the page's own pop-ups. Then it leaves.
                if (overlay.visibility == View.VISIBLE) {
                    popupHide()
                    return
                }
                web.evaluateJavascript("!!(window.onAppBack&&window.onAppBack())") { handled ->
                    if (handled != "true") {
                        if (web.canGoBack()) web.goBack() else finish()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Customer came back from the "allow installs" settings screen: continue where we left off.
        val job = waitingForPermission
        val url = waitingUrl
        if (job != null && url != null && canInstall()) {
            waitingForPermission = null
            waitingUrl = null
            startDownload(url, job)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        InstallEvents.onStatus = null
        ui.removeCallbacksAndMessages(null)
        try { unregisterReceiver(downloadDone) } catch (_: Exception) {}
        try { packageManager.packageInstaller.unregisterSessionCallback(sessionCb) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- the banner at the top of the screen
    private fun buildPopup() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun lp(top: Int, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = top }

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        stepViews = Array(3) { TextView(this) }          // kept up to date, not shown (the banner is compact)
        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#E6E1FF"))
            textSize = 15f
        }
        val tint = ColorStateList.valueOf(pink)
        barView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = tint
            indeterminateTintList = tint
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
        }
        hintView = TextView(this).apply {
            setTextColor(Color.parseColor("#B5AEDC"))
            textSize = 13f
        }

        fun pill(stroke: Boolean) = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#7C4DFF"), pink)
        ).apply {
            cornerRadius = dp(22).toFloat()
            if (stroke) setStroke(dp(3), Color.WHITE)
        }
        fun styled(label: String) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            minHeight = 0
            minimumHeight = 0
            isFocusable = true
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setTextColor(Color.WHITE)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), pillDrawable(true))
                addState(intArrayOf(), pillDrawable(false))
            }
        }
        doneButton = styled("Cancel").apply {
            setOnClickListener { if (jobs.isNotEmpty()) cancelDownload() else popupHide() }
        }
        openButton = styled("Open").apply {
            visibility = View.GONE
            setOnClickListener { openInstalled() }
        }
        // the d-pad moves between the two buttons (left/right)
        openButton.id = View.generateViewId()
        doneButton.id = View.generateViewId()
        openButton.nextFocusRightId = doneButton.id
        doneButton.nextFocusLeftId = openButton.id

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView, lp(0))
            addView(statusView, lp(dp(2)))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(openButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
            addView(doneButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        }

        popupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B1535"))
                setStroke(dp(2), pink)
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(12).toFloat()
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(row, lp(0))
            addView(barView, lp(dp(8), dp(10)))
            addView(hintView, lp(dp(6)))
        }

        // Transparent and not clickable: the page underneath keeps working, only the banner sits on top, at the top.
        overlay = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = false
            val m = dp(12)
            addView(
                popupCard,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
                    .apply { setMargins(m, m, m, 0) }
            )
        }
    }

    private fun pillDrawable(stroke: Boolean): GradientDrawable {
        val d = resources.displayMetrics.density
        return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(accent, pink)).apply {
            cornerRadius = (22 * d)
            if (stroke) setStroke((3 * d).toInt(), Color.WHITE)
        }
    }

    /** Colours chosen in the admin panel (sent by the store page): the banner follows the website's theme. */
    private fun applyTheme(card: Int, ac: Int, ac2: Int, tx: Int, bg: Int) {
        rootView.setBackgroundColor(bg)
        @Suppress("DEPRECATION") try { window.statusBarColor = bg } catch (_: Exception) {}
        accent = ac; pink = ac2; cardColor = card; textColor = tx
        val d = resources.displayMetrics.density
        popupCard.background = GradientDrawable().apply {
            setColor(card)
            setStroke((2 * d).toInt(), ac2)
            cornerRadius = 18 * d
        }
        titleView.setTextColor(tx)
        statusView.setTextColor(tx)
        val tint = ColorStateList.valueOf(ac2)
        barView.progressTintList = tint
        barView.indeterminateTintList = tint
        for (b in listOf(doneButton, openButton)) {
            b.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), pillDrawable(true))
                addState(intArrayOf(), pillDrawable(false))
            }
        }
        renderSteps()
    }

    private fun cancelDownload() {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        for ((id, job) in jobs.toMap()) {
            try { dm.remove(id) } catch (_: Exception) {}
            job.file.delete()
        }
        jobs.clear()
        popupHide()
        toast("Download cancelled")
    }

    private fun renderSteps() {
        for (i in 0..2) {
            val symbol = when (stepStates[i]) {
                ACTIVE -> "●"
                DONE -> "✓"
                FAILED -> "✗"
                SKIPPED -> "–"
                else -> "○"
            }
            val color = when (stepStates[i]) {
                ACTIVE -> pink
                DONE -> green
                FAILED -> red
                else -> gray
            }
            stepViews[i].text = "$symbol  ${stepNames[i]}"
            stepViews[i].setTextColor(color)
        }
    }

    private fun setStep(i: Int, state: Int) {
        stepStates[i] = state
        renderSteps()
    }

    /** percent = null shows a moving bar when the real percentage isn't known. */
    private fun setStatus(text: String, percent: Int?, hint: String) {
        statusView.text = text
        if (percent == null) {
            barView.isIndeterminate = true
        } else {
            barView.isIndeterminate = false
            barView.progress = percent
        }
        hintView.text = hint
        hintView.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
        keepFocus()
    }

    /** Puts the remote's cursor on the banner (its button, or the banner itself) and keeps it there. */
    private fun keepFocus() {
        if (overlay.visibility != View.VISIBLE) return
        if (doneButton.hasFocus() || openButton.hasFocus()) return          // already on a button: leave it there
        when {
            openButton.visibility == View.VISIBLE -> openButton.requestFocus()   // after an install: "Open" first
            doneButton.visibility == View.VISIBLE -> doneButton.requestFocus()
            !popupCard.hasFocus() -> popupCard.requestFocus()
        }
    }

    private fun popupShow() {
        overlay.visibility = View.VISIBLE
        // the page behind can't take the cursor while the banner is up
        web.isFocusable = false
        web.isFocusableInTouchMode = false
        web.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        keepFocus()
    }

    private fun popupHide() {
        overlay.visibility = View.GONE
        web.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.requestFocus()
    }

    private fun popupStart(name: String) {
        titleView.text = name
        stepStates.fill(PENDING)
        stepStates[0] = ACTIVE
        renderSteps()
        doneButton.text = "Cancel"
        doneButton.visibility = View.VISIBLE
        openButton.visibility = View.GONE
        setStatus("Downloading… 0%", 0, "")
        popupShow()
    }

    /** Last screen of the pop-up: what happened, and a Done button. */
    private fun popupDone(ok: Boolean, name: String, deletedLine: String) {
        val head = if (ok) "$name installed ✓" else "$name was not installed"
        val body = if (ok) "$name is ready to open." else "The install was cancelled or didn't finish. You can try again."
        setStatus(head, if (ok) 100 else 0, "$body $deletedLine")
        doneButton.text = "Done"
        doneButton.visibility = View.VISIBLE
        openButton.visibility = if (ok && lastPkg != null) View.VISIBLE else View.GONE
        popupShow()
        popupCard.clearFocus()
        if (openButton.visibility == View.VISIBLE) openButton.requestFocus() else doneButton.requestFocus()
    }

    /** Opens the app that was just installed (TV launcher first, then the normal one). */
    private fun openInstalled() {
        val pkg = lastPkg ?: return
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast("Couldn't open it. Find it in your apps."); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        popupHide()
        startActivity(i)
    }

    /** The install is over (worked or not): show the delete step, delete the installer, show the result. */
    private fun finishInstall(name: String, path: String, deleteAfter: Boolean, ok: Boolean) {
        // read the app's package name from the installer before it is deleted, so "Open" knows what to launch
        lastPkg = try {
            if (ok && path.isNotEmpty()) packageManager.getPackageArchiveInfo(path, 0)?.packageName else null
        } catch (_: Exception) { null }
        setStep(1, if (ok) DONE else FAILED)
        if (!deleteAfter) {
            setStep(2, SKIPPED)
            popupDone(ok, name, "The installer was kept on your device.")
            return
        }
        setStep(2, ACTIVE)
        setStatus("Deleting the installer…", null, "")
        doneButton.visibility = View.GONE
        openButton.visibility = View.GONE
        popupShow()
        Thread {
            if (path.isNotEmpty()) File(path).delete()
            ui.postDelayed({
                setStep(2, DONE)
                popupDone(ok, name, "The installer was deleted to free up space.")
            }, 900)   // short pause so the delete step can be seen
        }.start()
    }

    // ---------------------------------------------------------------- JS bridge
    inner class Bridge {
        @JavascriptInterface
        fun install(url: String, name: String, deleteAfter: Boolean) {
            runOnUiThread { beginInstall(url, name, deleteAfter) }
        }

        @JavascriptInterface
        fun setTheme(card: String, ac: String, ac2: String, tx: String, bg: String) {
            try {
                val c = Color.parseColor(card); val a = Color.parseColor(ac)
                val b = Color.parseColor(ac2); val t = Color.parseColor(tx); val g = Color.parseColor(bg)
                runOnUiThread { applyTheme(c, a, b, t, g) }
            } catch (_: Exception) {}   // unknown colour format: keep the current colours
        }

        @JavascriptInterface
        fun openLink(url: String) {
            runOnUiThread { openExternal(url) }
        }
    }

    private fun openExternal(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- download
    private fun beginInstall(url: String, name: String, deleteAfter: Boolean) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: run {
            toast("No storage available"); return
        }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app" }
        val file = File(dir, "$safe.apk")
        val job = InstallJob(file, deleteAfter, name)

        if (!canInstall()) {
            waitingForPermission = job
            waitingUrl = url
            toast("Allow Popcorn to install apps, then come back")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        startDownload(url, job)
    }

    private fun startDownload(url: String, job: InstallJob) {
        if (job.file.exists()) job.file.delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(job.name)
            .setDestinationUri(Uri.fromFile(job.file))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        jobs[dm.enqueue(req)] = job
        popupStart(job.name)
        if (!polling) {
            polling = true
            ui.post(poll)
        }
    }

    private fun onDownloaded(id: Long, job: InstallJob) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            val ok = c.moveToFirst() &&
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
            if (ok) {
                setStep(0, DONE)
                setStep(1, ACTIVE)
                setStatus("Starting the install…", null, "")
                doneButton.visibility = View.GONE
                keepFocus()
                installFile(job)
            } else {
                job.file.delete()
                setStep(0, FAILED)
                setStatus("Download failed", 0, "Check your connection and try again.")
                doneButton.text = "Done"
                doneButton.visibility = View.VISIBLE
                popupShow()
            }
        }
    }

    // ---------------------------------------------------------------- install
    private fun installFile(job: InstallJob) {
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            sessionJobs[sessionId] = job
            installer.openSession(sessionId).use { session ->
                job.file.inputStream().use { input ->
                    session.openWrite("app", 0, job.file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // The result is handled in InstallReceiver, which tells this screen.
                val result = Intent(this, InstallReceiver::class.java)
                    .putExtra(InstallReceiver.EXTRA_PATH, job.file.absolutePath)
                    .putExtra(InstallReceiver.EXTRA_DELETE, job.deleteAfter)
                    .putExtra(InstallReceiver.EXTRA_NAME, job.name)
                val pending = PendingIntent.getBroadcast(
                    this, sessionId, result,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            sessionJobs.values.removeAll { it.name == job.name }
            finishInstall(job.name, job.file.absolutePath, job.deleteAfter, false)
        }
    }

    // Android 8+ asks per app; Android 7 (older Fire TV sticks) has no such check
    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
