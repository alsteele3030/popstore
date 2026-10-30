package com.popcorn.store

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/** One pending download: where the APK is, whether to delete it afterwards, and its display name. */
data class InstallJob(val file: File, val deleteAfter: Boolean, val name: String)

class MainActivity : AppCompatActivity() {

    companion object {
        val jobs = mutableMapOf<Long, InstallJob>()
    }

    private lateinit var web: WebView
    private var waitingForPermission: InstallJob? = null
    private var waitingUrl: String? = null

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val job = jobs.remove(id) ?: return
            onDownloaded(id, job)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
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
        try { unregisterReceiver(downloadDone) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- JS bridge
    inner class Bridge {
        @JavascriptInterface
        fun install(url: String, name: String, deleteAfter: Boolean) {
            runOnUiThread { beginInstall(url, name, deleteAfter) }
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
        toast("Downloading ${job.name}…")
    }

    private fun onDownloaded(id: Long, job: InstallJob) {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            val ok = c.moveToFirst() &&
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
            if (ok) installFile(job) else {
                job.file.delete()
                toast("Download failed. Please try again.")
            }
        }
    }

    // ---------------------------------------------------------------- install
    private fun installFile(job: InstallJob) {
        try {
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                job.file.inputStream().use { input ->
                    session.openWrite("app", 0, job.file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // The result (including the delete step) is handled in InstallReceiver.
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
            if (job.deleteAfter) job.file.delete()
            toast("Could not install ${job.name}")
        }
    }

    // Android 8+ asks per app; Android 7 (older Fire TV sticks) has no such check
    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
