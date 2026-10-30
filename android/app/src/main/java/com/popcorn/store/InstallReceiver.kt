package com.popcorn.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

/**
 * Receives the result of an install. When the app screen is open it takes over (shows the result and the
 * "delete the installer" step). If the screen is closed, this receiver deletes the installer itself and shows a toast.
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_DELETE = "delete"
        const val EXTRA_NAME = "name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME) ?: "App"
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val path = intent.getStringExtra(EXTRA_PATH) ?: ""
        val deleteAfter = intent.getBooleanExtra(EXTRA_DELETE, true)

        val listener = InstallEvents.onStatus
        val screenTookOver = listener?.invoke(name, status, path, deleteAfter) ?: false

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants the customer to tap "Install". Show that screen; we are not done yet.
                val confirm: Intent? =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                if (!screenTookOver) cleanup(path, deleteAfter)
                if (listener == null) Toast.makeText(context, "$name installed", Toast.LENGTH_LONG).show()
            }

            else -> {
                if (!screenTookOver) cleanup(path, deleteAfter)
                if (listener == null) Toast.makeText(context, "$name was not installed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cleanup(path: String, deleteAfter: Boolean) {
        if (!deleteAfter || path.isEmpty()) return
        File(path).delete()
    }
}
